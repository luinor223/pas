package com.abclogistics.pas.contract.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * File attached to a contract or addendum. Polymorphic owner rather than two tables — one upload
 * UI, two owner kinds, and no FK (hence {@link EntityType} + a raw {@code ownerId}).
 *
 * <p>Bytes live behind the attachment-storage abstraction. The legacy {@code storagePath} column
 * contains that implementation's opaque key (an absolute path for the mounted-volume
 * implementation); only metadata is stored here. CTR-02's ">= 1 attachment" is an application
 * check at submit, not a DB constraint — a DRAFT is allowed to have none.
 */
@Entity
@Table(name = "attachment", schema = "contract")
public class Attachment {

    @Id
    @UuidGenerator
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, updatable = false)
    private EntityType ownerType;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected Attachment() { } // JPA

    public static Attachment create(EntityType ownerType, UUID ownerId, String fileName,
                                    String contentType, Long sizeBytes, String storagePath,
                                    UUID uploadedBy) {
        Attachment a = new Attachment();
        a.ownerType = ownerType;
        a.ownerId = ownerId;
        a.fileName = fileName;
        a.contentType = contentType;
        a.sizeBytes = sizeBytes;
        a.storagePath = storagePath;
        a.uploadedBy = uploadedBy;
        a.uploadedAt = Instant.now();
        return a;
    }

    public UUID getId() { return id; }
    public EntityType getOwnerType() { return ownerType; }
    public UUID getOwnerId() { return ownerId; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public String getStoragePath() { return storagePath; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
}
