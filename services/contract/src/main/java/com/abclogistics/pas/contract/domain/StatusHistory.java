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
 * Append-only transition log (D17). One row is written in the SAME transaction as every status
 * column change, so {@code Contract.status} / {@code Addendum.status} is a cache of the newest
 * row here and the two are reconcilable by construction — a status change with no history row is
 * a bug.
 *
 * <p>INSERT and SELECT only: never updated, never deleted. This is the only history a business
 * rule may read; audit-service is remote, eventually consistent and uninterpreted, so a rule must
 * never depend on it.
 *
 * <p>An order-tolerant apply (registry §9 footnote ¹) writes one row per edge — a
 * {@code workflow.completed} arriving while still SUBMITTED writes the skipped
 * SUBMITTED → UNDER_REVIEW row and then the outcome row, both in one transaction.
 */
@Entity
@Table(name = "status_history", schema = "contract")
public class StatusHistory {

    @Id
    @UuidGenerator
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, updatable = false)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    /** null only for the very first row of an entity's life. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private DocumentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private DocumentStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", nullable = false, updatable = false)
    private TriggerKind triggerKind;

    /** Workflow instance id, esign session id, or null for a user/scheduler action. */
    @Column(name = "trigger_ref", updatable = false)
    private UUID triggerRef;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "actor_name", updatable = false)
    private String actorName;

    @Column(updatable = false)
    private String note;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected StatusHistory() { } // JPA

    public static StatusHistory of(EntityType entityType, UUID entityId,
                                   DocumentStatus fromStatus, DocumentStatus toStatus,
                                   TriggerKind triggerKind, UUID triggerRef,
                                   UUID actorId, String actorName, String note) {
        StatusHistory h = new StatusHistory();
        h.entityType = entityType;
        h.entityId = entityId;
        h.fromStatus = fromStatus;
        h.toStatus = toStatus;
        h.triggerKind = triggerKind;
        h.triggerRef = triggerRef;
        h.actorId = actorId;
        h.actorName = actorName;
        h.note = note;
        h.occurredAt = Instant.now();
        return h;
    }

    public UUID getId() { return id; }
    public EntityType getEntityType() { return entityType; }
    public UUID getEntityId() { return entityId; }
    public DocumentStatus getFromStatus() { return fromStatus; }
    public DocumentStatus getToStatus() { return toStatus; }
    public TriggerKind getTriggerKind() { return triggerKind; }
    public UUID getTriggerRef() { return triggerRef; }
    public UUID getActorId() { return actorId; }
    public String getActorName() { return actorName; }
    public String getNote() { return note; }
    public Instant getOccurredAt() { return occurredAt; }
}
