package com.abclogistics.pas.esign.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "status_history", schema = "esign")
public class StatusHistory {

    public enum TriggerKind {
        U, W, E, S
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private SigningSession session;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status", nullable = false)
    private String toStatus;

    @Column(name = "trigger_kind", nullable = false)
    @Enumerated(EnumType.STRING)
    private TriggerKind triggerKind;

    @Column(name = "trigger_ref")
    private UUID triggerRef;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_name")
    private String actorName;

    @Column(name = "note")
    private String note;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected StatusHistory() {}

    public static StatusHistory create(SigningSession session, String fromStatus, String toStatus,
                                        TriggerKind triggerKind, UUID triggerRef,
                                        UUID actorId, String actorName, String note) {
        StatusHistory h = new StatusHistory();
        h.id = UUID.randomUUID();
        h.session = session;
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

    // Getters
    public UUID getId() { return id; }
    public SigningSession getSession() { return session; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public TriggerKind getTriggerKind() { return triggerKind; }
    public UUID getTriggerRef() { return triggerRef; }
    public UUID getActorId() { return actorId; }
    public String getActorName() { return actorName; }
    public String getNote() { return note; }
    public Instant getOccurredAt() { return occurredAt; }

    // Setters
    public void setSession(SigningSession session) { this.session = session; }
}
