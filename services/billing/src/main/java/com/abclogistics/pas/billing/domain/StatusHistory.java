package com.abclogistics.pas.billing.domain;

import com.abclogistics.pas.common.persistence.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "status_history", schema = "billing")
public class StatusHistory extends BaseEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "statement_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_status_history_statement"))
    private PaymentStatement statement;

    @Column(name = "from_status", length = 30)
    private String fromStatus;

    @Column(name = "to_status", length = 30, nullable = false)
    private String toStatus;

    @Column(name = "trigger_kind", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private TriggerKind triggerKind;

    @Column(name = "trigger_ref", length = 200)
    private String triggerRef;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_name", length = 200)
    private String actorName;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public StatusHistory() {}

    public enum TriggerKind {
        U, S, W, E
    }

    public UUID getId() { return id; }
    public PaymentStatement getStatement() { return statement; }
    public void setStatement(PaymentStatement statement) { this.statement = statement; }
    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }
    public TriggerKind getTriggerKind() { return triggerKind; }
    public void setTriggerKind(TriggerKind triggerKind) { this.triggerKind = triggerKind; }
    public String getTriggerRef() { return triggerRef; }
    public void setTriggerRef(String triggerRef) { this.triggerRef = triggerRef; }
    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }
    public String getActorName() { return actorName; }
    public void setActorName(String actorName) { this.actorName = actorName; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}
