package com.abclogistics.pas.contract.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_event", schema = "contract")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedEvent() { } // JPA

    public static ProcessedEvent of(UUID eventId) {
        ProcessedEvent e = new ProcessedEvent();
        e.eventId = eventId;
        e.processedAt = Instant.now();
        return e;
    }

    public UUID getEventId() { return eventId; }
    public Instant getProcessedAt() { return processedAt; }
}
