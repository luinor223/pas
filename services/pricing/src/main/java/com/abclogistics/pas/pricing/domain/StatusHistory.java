package com.abclogistics.pas.pricing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Append-only transition log for a version (D17). Written in the same tx as the status change. */
@Entity
@Table(name = "status_history", schema = "pricing")
public class StatusHistory {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private PriceListVersionStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private PriceListVersionStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", nullable = false)
    private TriggerKind triggerKind;

    @Column(name = "trigger_ref")
    private UUID triggerRef;

    @Column(name = "note")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    protected StatusHistory() {}

    public StatusHistory(UUID versionId, PriceListVersionStatus fromStatus, PriceListVersionStatus toStatus,
                         TriggerKind triggerKind, UUID triggerRef, String note, UUID createdBy) {
        this.versionId = versionId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.triggerKind = triggerKind;
        this.triggerRef = triggerRef;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getVersionId() { return versionId; }
    public PriceListVersionStatus getFromStatus() { return fromStatus; }
    public PriceListVersionStatus getToStatus() { return toStatus; }
    public TriggerKind getTriggerKind() { return triggerKind; }
    public UUID getTriggerRef() { return triggerRef; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getCreatedBy() { return createdBy; }
}
