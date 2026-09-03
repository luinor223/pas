package com.abclogistics.pas.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Consumer-side dedup (D6). PK is the envelope {@code event_id}, and the only writer is
 *  {@code ProcessedEventRepository#claim} — there is no way to construct one by hand. */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() { }

    public UUID getId() { return id; }
    public Instant getProcessedAt() { return processedAt; }
}
