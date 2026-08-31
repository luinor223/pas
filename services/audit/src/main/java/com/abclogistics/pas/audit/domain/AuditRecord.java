package com.abclogistics.pas.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One immutable audit row (4.10). Deliberately not a {@code BaseEntity}: there is no
 * {@code updated_at}, no {@code version} and no setter, because the table grants INSERT + SELECT
 * only — a business service cannot rewrite its own history (db-audit.md).
 *
 * <p>{@code id} is the producer's outbox row id, i.e. the envelope {@code event_id}. That is what
 * makes the PK the dedup key and is why this service needs no {@code processed_event}.
 */
@Entity
@Table(name = "audit_record")
public class AuditRecord {

    @Id
    private UUID id;

    @Column(name = "source_service", nullable = false, updatable = false)
    private String sourceService;

    @Column(name = "entity_type", nullable = false, updatable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @Column(name = "entity_no", updatable = false)
    private String entityNo;

    @Column(nullable = false, updatable = false)
    private String action;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "actor_name", updatable = false)
    private String actorName;

    @Column(name = "actor_department", updatable = false)
    private String actorDepartment;

    @Column(name = "before_status", updatable = false)
    private String beforeStatus;

    @Column(name = "after_status", updatable = false)
    private String afterStatus;

    /** Stored and returned, never interpreted — that is what stops this becoming a god-service. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private Map<String, Object> changes;

    @Column(updatable = false, length = 2000)
    private String note;

    @Column(name = "ip_address", updatable = false)
    private String ipAddress;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AuditRecord() { }

    public static AuditRecord of(UUID eventId, String sourceService, String entityType,
                                 UUID entityId, String entityNo, String action,
                                 UUID actorId, String actorName, String actorDepartment,
                                 String beforeStatus, String afterStatus,
                                 Map<String, Object> changes, String note, String ipAddress,
                                 Instant occurredAt) {
        AuditRecord r = new AuditRecord();
        r.id = eventId;
        r.sourceService = sourceService;
        r.entityType = entityType;
        r.entityId = entityId;
        r.entityNo = entityNo;
        r.action = action;
        r.actorId = actorId;
        r.actorName = actorName;
        r.actorDepartment = actorDepartment;
        r.beforeStatus = beforeStatus;
        r.afterStatus = afterStatus;
        r.changes = changes == null ? Map.of() : changes;
        r.note = note;
        r.ipAddress = ipAddress;
        r.occurredAt = occurredAt;
        return r;
    }

    public UUID getId() { return id; }
    public String getSourceService() { return sourceService; }
    public String getEntityType() { return entityType; }
    public UUID getEntityId() { return entityId; }
    public String getEntityNo() { return entityNo; }
    public String getAction() { return action; }
    public UUID getActorId() { return actorId; }
    public String getActorName() { return actorName; }
    public String getActorDepartment() { return actorDepartment; }
    public String getBeforeStatus() { return beforeStatus; }
    public String getAfterStatus() { return afterStatus; }
    public Map<String, Object> getChanges() { return changes; }
    public String getNote() { return note; }
    public String getIpAddress() { return ipAddress; }
    public Instant getOccurredAt() { return occurredAt; }
}
