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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
 */
@Service
public class AttachmentService {

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
        this.storageRoot = Path.of(storagePath);
    }

    @Transactional(readOnly = true)
    public List<Attachment> list(EntityType ownerType, UUID ownerId) {
        return attachments.findByOwnerTypeAndOwnerId(ownerType, ownerId);
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
        Attachment attachment = attachments.findById(id)
                .orElseThrow(() -> new NotFoundException("Attachment %s not found".formatted(id)));
        requireEditableOwner(attachment.getOwnerType(), attachment.getOwnerId());

        attachments.delete(attachment);
        // The row is the record of truth; a leftover file is inert, whereas deleting the bytes
        // before the transaction commits would lose them if it rolls back. Cleanup is a sweep.
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
}
