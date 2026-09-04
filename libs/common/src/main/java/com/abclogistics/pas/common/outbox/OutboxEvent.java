package com.abclogistics.pas.common.outbox;

import com.abclogistics.pas.common.correlation.CorrelationSupport;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional outbox row, written in the same transaction as its state change.
 * Maps into the service's {@code hibernate.default_schema} ({@code identity.outbox}, …).
 * {@link #id} is the event id; consumers dedup on it. {@code claimedAt} null means the
 * relay never claimed it, which the cancel path depends on.
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent {

    private static final String AUDIT_EVENT = "audit.recorded";
    private static final String AUDIT_TOPIC = "pas.audit";
    private static final String EVENTS_TOPIC = "pas.events";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    /** Kafka partition key, so one aggregate's events stay ordered. */
    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Correlation id of the request that produced this event, carried through to the Kafka header. */
    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    /** null = never claimed by the relay; the cancel path depends on this. */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    protected OutboxEvent() { } // JPA

    private OutboxEvent(String eventType, String aggregateType, UUID aggregateId, String payload) {
        this.id = UUID.randomUUID();
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.createdAt = Instant.now();
        this.correlationId = CorrelationSupport.current();
        this.retryCount = 0;
    }

    /** Test-only helper to control ordering in relay tests. */
    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    /** A business or workflow event. */
    public static OutboxEvent event(String eventType, String aggregateType, UUID aggregateId, String payload) {
        return new OutboxEvent(eventType, aggregateType, aggregateId, payload);
    }

    /** An {@code audit.recorded} record. */
    public static OutboxEvent audit(String aggregateType, UUID aggregateId, String payload) {
        return new OutboxEvent(AUDIT_EVENT, aggregateType, aggregateId, payload);
    }

    /** Destination topic, derived from the event type. */
    public String topic() {
        return AUDIT_EVENT.equals(eventType) ? AUDIT_TOPIC : EVENTS_TOPIC;
    }

    public void markClaimed() { this.claimedAt = Instant.now(); }

    /**
     * Failure path: drop the claim and count the attempt so the next poll reclaims immediately.
     * Public because the cancel handoff releases the claim from outside this package.
     */
    public void releaseClaim() {
        this.claimedAt = null;
        this.retryCount++;
    }
    public void markPublished() { this.publishedAt = Instant.now(); }
    public void markCancelled() { this.cancelledAt = Instant.now(); }
    public void incrementRetry() { this.retryCount++; }

    public UUID getId() { return id; }
    public String getEventType() { return eventType; }
    public String getAggregateType() { return aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCorrelationId() { return correlationId; }
    public Instant getClaimedAt() { return claimedAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public int getRetryCount() { return retryCount; }
}
