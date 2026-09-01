package com.abclogistics.pas.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Consumer-side dedup (D6). PK is the envelope {@code event_id}. */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() { }

    public static ProcessedEvent of(UUID eventId) {
        ProcessedEvent e = new ProcessedEvent();
        e.id = eventId;
        e.processedAt = Instant.now();
        return e;
    }

    public UUID getId() { return id; }
    public Instant getProcessedAt() { return processedAt; }
}
