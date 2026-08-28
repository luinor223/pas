package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.ForbiddenException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.contract.domain.Attachment;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.repository.AddendumRepository;
import com.abclogistics.pas.contract.repository.AttachmentRepository;
import com.abclogistics.pas.contract.repository.ContractRepository;
import com.abclogistics.pas.contract.storage.AttachmentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Attachment metadata and lifecycle. Bytes go to {@link AttachmentStorage} under a generated key;
 * the client's filename is display-only and never used to build one.
 *
 * <p>Permissions are per owner type ({@code contract:*} vs {@code addendum:*}, registry §10) and
 * so cannot be a {@code @PreAuthorize} — download and delete know the owner only after the read.
 *
 * <p>Bytes and row are two stores with no shared transaction, so writes reconcile them in a
 * transaction synchronization: rolled-back upload deletes its object, committed delete removes
 * the bytes only once the row is really gone.
 */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private static final String READ = "read";
    private static final String WRITE = "write";

    private final AttachmentRepository attachments;
    private final ContractRepository contracts;
    private final AddendumRepository addenda;
    private final AuditRecorder audit;
    private final AttachmentStorage storage;

    public AttachmentService(AttachmentRepository attachments, ContractRepository contracts,
                             AddendumRepository addenda, AuditRecorder audit,
                             AttachmentStorage storage) {
        this.attachments = attachments;
        this.contracts = contracts;
        this.addenda = addenda;
        this.audit = audit;
        this.storage = storage;
    }

    /** Metadata plus the bytes to stream, resolved together so the two cannot disagree. */
    public record AttachmentContent(Attachment metadata, Resource resource) { }

    @Transactional(readOnly = true)
    public List<Attachment> list(EntityType ownerType, UUID ownerId) {
        requirePermission(ownerType, READ);
        return attachments.findByOwnerTypeAndOwnerId(ownerType, ownerId);
    }

    @Transactional(readOnly = true)
    public Attachment get(UUID id) {
        return attachments.findById(id)
                .orElseThrow(() -> new NotFoundException("Attachment %s not found".formatted(id)));
    }

    /** Readable regardless of owner status: CTR-01 freezes the file set, it does not hide it. */
    @Transactional(readOnly = true)
    public AttachmentContent download(UUID id) {
        Attachment attachment = get(id);
        // After the read: the id alone does not say which document type this belongs to.
        requirePermission(attachment.getOwnerType(), READ);
        Resource resource;
        try {
            resource = storage.load(attachment.getStoragePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load attachment %s".formatted(id), e);
        }
        if (!resource.isReadable()) {
            // The row is the record of truth, so a missing object is a storage fault, not a 404.
            throw new IllegalStateException(
                    "Attachment %s has no readable object at its storage key".formatted(id));
        }
        return new AttachmentContent(attachment, resource);
    }

    /** Only while the owner is editable (CTR-01): an approved document's file set is fixed. */
    @Transactional
    public Attachment upload(EntityType ownerType, UUID ownerId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new UnprocessableEntityException("An empty file cannot be attached");
        }
        String fileName = requireFileName(file.getOriginalFilename());
        requirePermission(ownerType, WRITE);
        requireEditableOwner(ownerType, ownerId);

        UUID id = UUID.randomUUID();
        String storageKey;
        try (InputStream content = file.getInputStream()) {
            storageKey = storage.store(ownerType, id, content, file.getSize());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store attachment for %s %s".formatted(ownerType, ownerId), e);
        }
        deleteOnRollback(storageKey);

        Attachment attachment = Attachment.create(ownerType, ownerId,
                fileName, safeContentType(file.getContentType()), file.getSize(),
                storageKey, SecurityUtils.currentUserId());
        attachments.save(attachment);

        audit.record(ownerType.name(), ownerId, null, "ATTACH", null, null, null,
                Map.of("fileName", fileName, "sizeBytes", file.getSize()));
        return attachment;
    }

    @Transactional
    public void delete(UUID id) {
        Attachment attachment = get(id);
        requirePermission(attachment.getOwnerType(), WRITE);
        requireEditableOwner(attachment.getOwnerType(), attachment.getOwnerId());

        attachments.delete(attachment);
        deleteOnCommit(attachment.getStoragePath());

        audit.record(attachment.getOwnerType().name(), attachment.getOwnerId(), null, "DETACH",
                null, null, null, Map.of("fileName", attachment.getFileName()));
    }

    private static String requireFileName(String original) {
        String fileName = original == null ? null : original.trim();
        if (fileName == null || fileName.isEmpty()) {
            throw new UnprocessableEntityException("An attachment must have a file name");
        }
        return fileName;
    }

    /** Client-supplied: an unparseable type stored verbatim makes the file undownloadable. */
    public static String safeContentType(String declared) {
        if (declared == null || declared.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        try {
            return MediaType.parseMediaType(declared).toString();
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }

    /** Holding {@code contract:*} has never implied {@code addendum:*} (registry §10). */
    private void requirePermission(EntityType ownerType, String verb) {
        String permission = ownerType.name().toLowerCase(Locale.ROOT) + ":" + verb;
        if (!SecurityUtils.hasPermission(permission)) {
            throw new ForbiddenException(
                    "%s is required to %s attachments of a %s".formatted(permission, verb, ownerType));
        }
    }

    private void requireEditableOwner(EntityType ownerType, UUID ownerId) {
        DocumentStatus status = switch (ownerType) {
            case CONTRACT -> contracts.findById(ownerId)
                    .orElseThrow(() -> new NotFoundException("Contract %s not found".formatted(ownerId)))
                    .getStatus();
            case ADDENDUM -> addenda.findById(ownerId)
                    .orElseThrow(() -> new NotFoundException("Addendum %s not found".formatted(ownerId)))
                    .getStatus();
        };
        if (!status.isEditable()) {
            throw new ConflictException(
                    "%s %s is %s; its attachments can no longer be changed (CTR-01)"
                            .formatted(ownerType, ownerId, status));
        }
    }

    private void deleteOnRollback(String storageKey) {
        onCompletion(status -> {
            if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                quietlyDelete(storageKey);
            }
        }, storageKey);
    }

    private void deleteOnCommit(String storageKey) {
        onCompletion(status -> {
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                quietlyDelete(storageKey);
            }
        }, storageKey);
    }

    private void onCompletion(java.util.function.IntConsumer action, String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Proxy bypassed. Leaving the object is the safe half: an orphan is inert.
            log.warn("No transaction synchronization active; leaving {} to the cleanup sweep", storageKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                action.accept(status);
            }
        });
    }

    /** Post-completion, so a failed delete cannot be escalated — the sweep retries it. */
    private void quietlyDelete(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (IOException e) {
            log.warn("Failed to delete attachment object {}; the cleanup sweep will retry it", storageKey, e);
        }
    }
}
