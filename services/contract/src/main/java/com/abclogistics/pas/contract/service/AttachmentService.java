package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.contract.domain.Attachment;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.repository.AddendumRepository;
import com.abclogistics.pas.contract.repository.AttachmentRepository;
import com.abclogistics.pas.contract.repository.ContractRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Attachment metadata + volume-backed storage. Bytes go to the mounted path configured by
 * {@code contract.attachment-storage-path}; only metadata is persisted.
 *
 * <p>Files are stored under a generated name, never the client's. An uploaded filename is
 * attacker-controlled text — {@code ../../etc/passwd} is a valid string — so it is kept for
 * display only and never used to build a path.
 *
 * <p>The bytes and the row are two stores with no shared transaction, so every write registers a
 * transaction synchronization to reconcile them: a rolled-back upload deletes the file it wrote,
 * and a committed delete removes the bytes only after the row is actually gone. Doing either
 * eagerly is wrong in the opposite direction — deleting before commit loses the file if the
 * transaction rolls back.
 */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private final AttachmentRepository attachments;
    private final ContractRepository contracts;
    private final AddendumRepository addenda;
    private final AuditRecorder audit;
    private final Path storageRoot;

    public AttachmentService(AttachmentRepository attachments, ContractRepository contracts,
                             AddendumRepository addenda, AuditRecorder audit,
                             @Value("${contract.attachment-storage-path}") String storagePath) {
        this.attachments = attachments;
        this.contracts = contracts;
        this.addenda = addenda;
        this.audit = audit;
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
    }

    /** Metadata plus the bytes to stream, resolved together so the two cannot disagree. */
    public record AttachmentContent(Attachment metadata, Resource resource) { }

    @Transactional(readOnly = true)
    public List<Attachment> list(EntityType ownerType, UUID ownerId) {
        return attachments.findByOwnerTypeAndOwnerId(ownerType, ownerId);
    }

    @Transactional(readOnly = true)
    public Attachment get(UUID id) {
        return attachments.findById(id)
                .orElseThrow(() -> new NotFoundException("Attachment %s not found".formatted(id)));
    }

    /**
     * Readable regardless of owner status — CTR-01 freezes the file set, it does not hide it.
     * An approved contract's attachments are exactly what must stay readable.
     */
    @Transactional(readOnly = true)
    public AttachmentContent download(UUID id) {
        Attachment attachment = get(id);
        Path file = containedPath(attachment);
        if (!Files.isReadable(file)) {
            // The row is the record of truth, so a missing file is a storage fault, not a 404.
            throw new IllegalStateException(
                    "Attachment %s has no readable file at its storage path".formatted(id));
        }
        return new AttachmentContent(attachment, new FileSystemResource(file));
    }

    /**
     * Allowed only while the owner is editable (CTR-01) — an approved document's file set is fixed,
     * or the thing that was approved is not the thing on file.
     */
    @Transactional
    public Attachment upload(EntityType ownerType, UUID ownerId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new UnprocessableEntityException("An empty file cannot be attached");
        }
        requireEditableOwner(ownerType, ownerId);

        UUID id = UUID.randomUUID();
        Path destination = storageRoot.resolve(ownerType.name().toLowerCase()).resolve(id.toString());
        try {
            Files.createDirectories(destination.getParent());
            file.transferTo(destination);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store attachment for %s %s".formatted(ownerType, ownerId), e);
        }
        deleteOnRollback(destination);

        Attachment attachment = Attachment.create(ownerType, ownerId,
                file.getOriginalFilename(), file.getContentType(), file.getSize(),
                destination.toString(), SecurityUtils.currentUserId());
        attachments.save(attachment);

        audit.record(ownerType.name(), ownerId, null, "ATTACH", null, null, null,
                Map.of("fileName", String.valueOf(file.getOriginalFilename()),
                        "sizeBytes", file.getSize()));
        return attachment;
    }

    @Transactional
    public void delete(UUID id) {
        Attachment attachment = get(id);
        requireEditableOwner(attachment.getOwnerType(), attachment.getOwnerId());

        Path file = containedPath(attachment);
        attachments.delete(attachment);
        deleteOnCommit(file);

        audit.record(attachment.getOwnerType().name(), attachment.getOwnerId(), null, "DETACH",
                null, null, null, Map.of("fileName", attachment.getFileName()));
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

    /**
     * Every path that leaves this class is re-checked against the storage root. The stored path is
     * server-generated today, but a read or delete driven by a database value must not be one bad
     * row away from touching an arbitrary file.
     */
    private Path containedPath(Attachment attachment) {
        Path file = Path.of(attachment.getStoragePath()).toAbsolutePath().normalize();
        if (!file.startsWith(storageRoot)) {
            throw new IllegalStateException(
                    "Attachment %s points outside the storage root".formatted(attachment.getId()));
        }
        return file;
    }

    private void deleteOnRollback(Path file) {
        onCompletion(status -> {
            if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                quietlyDelete(file);
            }
        }, file);
    }

    private void deleteOnCommit(Path file) {
        onCompletion(status -> {
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                quietlyDelete(file);
            }
        }, file);
    }

    private void onCompletion(java.util.function.IntConsumer action, Path file) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Both callers are @Transactional, so this means someone bypassed the proxy. Leaving
            // the file is the safe half of the trade: an orphan is inert, a lost file is not.
            log.warn("No transaction synchronization active; leaving {} for a cleanup sweep", file);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                action.accept(status);
            }
        });
    }

    private void quietlyDelete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // After completion there is no transaction left to fail. An undeleted file is inert.
            log.warn("Failed to delete attachment file {}", file, e);
        }
    }
}
