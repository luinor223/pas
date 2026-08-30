package com.abclogistics.pas.common.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Abstract transactional outbox relay — implements mechanics.md M2.
 * <p>
 * <b>Poll:</b> {@code WHERE published_at IS NULL AND cancelled_at IS NULL AND (claimed_at IS NULL OR claimed_at < now() - lease) ORDER BY created_at}
 * <b>Claim:</b> {@code UPDATE ... SET claimed_at = now() WHERE id = ? AND published_at IS NULL AND cancelled_at IS NULL AND (claimed_at IS NULL OR claimed_at < now() - lease)}
 * 0 rows means a concurrent worker already claimed/published/cancelled — skip.
 * <p>
 * Then {@link #dispatch(OutboxEvent)} — for Kafka publish, only after {@code acks=all} ack stamp {@code published_at};
 * for gRPC dispatch, after the remote call succeeds. On failure clear {@code claimed_at} + {@code retry_count++} so
 * the next poll reclaims immediately (not waiting for lease expiry — clearing isn't load-bearing but avoids delay).
 * <p>
 * Ordering: poll is {@code ORDER BY created_at} and one publisher runs per service (single relay instance). This
 * preserves per-document commit order to Kafka's per-partition order; the claim protocol exists for crash recovery
 * during rolling restarts, not for parallel publishing.
 * <p>
 * Lifecycle: PENDING (all timestamps null) → PROCESSING (claimed_at set) → DONE (published_at set),
 * side exit PENDING → CANCELLED (cancelled_at) only from true PENDING (claimed_at IS NULL) — stale claims are not cancellable.
 * <p>
 * <b>Transactions are explicit, not annotated.</b> Every database step below is reached from
 * {@link #pollAndDispatch()} by a plain call, and a Spring transaction proxy does not intercept
 * self-invocation — an {@code @Transactional} here would silently do nothing, leaving the
 * pessimistic-lock poll and the bulk-update claim with no transaction to run in. Each step also
 * has to commit on its own: the claim must be visible before the remote dispatch begins, and the
 * dispatch must not be made while holding the connection that will record its outcome.
 */
public abstract class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final OutboxRelayProperties props;
    private final TransactionTemplate tx;

    protected OutboxRelay(OutboxRepository outbox, OutboxRelayProperties props, TransactionTemplate tx) {
        this.outbox = outbox;
        this.props = props;
        this.tx = tx;
    }

    /**
     * Deliver the event to its external target (Kafka or gRPC). Must be idempotent on the producer side
     * (Kafka acks=all + idempotence, gRPC idempotency_key) and must throw on failure so the relay can retry.
     */
    protected abstract void dispatch(OutboxEvent event) throws Exception;

    /**
     * Hook to resolve the destination (topic or gRPC method) — default uses {@link OutboxEvent#topic()}.
     * Override if dispatch distinguishes workflow.start_requested vs audit.recorded.
     */
    protected String destination(OutboxEvent event) {
        return event.topic();
    }

    /**
     * The wire shape every service publishes and every consumer reads. Key is the aggregate id, so
     * one document's events stay in one partition and therefore in order.
     *
     * <p>{@code event_id} is the outbox row id and the consumer's dedup key — the value it stores
     * in {@code processed_event}. Without it on the record a consumer cannot tell a redelivery
     * from a new event, so it is part of the contract, not a convenience.
     */
    protected ProducerRecord<String, String> kafkaRecord(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                event.topic(), event.getAggregateId().toString(), event.getPayload());
        record.headers().add(header("event_id", event.getId().toString()));
        record.headers().add(header("event_type", event.getEventType()));
        record.headers().add(header("document_type", event.getAggregateType()));
        return record;
    }

    private static RecordHeader header(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }

    // the property, not #{@outboxRelayProperties…}: @EnableConfigurationProperties registers the
    // bean under a generated name, so the SpEL reference resolves to nothing and startup fails
    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval:PT5S}")
    public void relay() {
        if (!props.enabled()) {
            return;
        }
        try {
            pollAndDispatch();
        } catch (Exception e) {
            log.warn("Outbox relay cycle failed: {}", e.getMessage(), e);
        }
    }

    public void pollAndDispatch() {
        Instant now = Instant.now();
        Instant staleThreshold = now.minus(props.claimLease());
        List<OutboxEvent> batch = pollBatch(staleThreshold);
        if (batch.isEmpty()) {
            return;
        }
        log.debug("Outbox relay polling {} events (staleThreshold={}, lease={})", batch.size(), staleThreshold, props.claimLease());
        for (OutboxEvent event : batch) {
            // Attempt atomic claim — same predicate as poll, so stale claims are reclaimable but cancellable only via separate path
            boolean claimed = tryClaim(event.getId(), now, staleThreshold);
            if (!claimed) {
                log.debug("Outbox event {} claim lost (concurrent worker won), skipping", event.getId());
                continue;
            }
            // Reflect claim in the detached batch instance for dispatch context (payload contains idempotency_key)
            event.setClaimedAt(now);
            try {
                dispatch(event);
                markPublished(event.getId());
                log.debug("Outbox event {} dispatched to {} and marked published", event.getId(), destination(event));
            } catch (Exception e) {
                if (isPermanentFailure(e)) {
                    // Retrying a refusal is not resilience: the row would be re-claimed every poll
                    // for ever, and the answer would be the same every time.
                    log.error("Outbox dispatch permanently refused for event {} ({}) to {}: {} —"
                                    + " parking the row, it will not be retried",
                            event.getId(), event.getEventType(), destination(event), e.getMessage());
                    markParked(event.getId(), e);
                } else {
                    log.warn("Outbox dispatch failed for event {} ({}), will retry: {}", event.getId(), event.getEventType(), e.getMessage());
                    markFailed(event.getId());
                }
            }
        }
    }

    protected List<OutboxEvent> pollBatch(Instant staleThreshold) {
        List<OutboxEvent> batch = tx.execute(status ->
                outbox.findUnpublishedForRelay(staleThreshold, Limit.of(props.batchSize())));
        return batch == null ? List.of() : batch;
    }

    protected boolean tryClaim(java.util.UUID id, Instant now, Instant staleThreshold) {
        return Boolean.TRUE.equals(tx.execute(status -> outbox.claim(id, now, staleThreshold) == 1));
    }

    protected void markPublished(java.util.UUID id) {
        tx.executeWithoutResult(status -> outbox.findById(id).ifPresent(e -> {
            e.markPublished();
            if (e.getClaimedAt() == null) {
                e.markClaimed();
            }
            outbox.save(e);
        }));
    }

    /**
     * Is this failure an answer rather than an outage? Default false — every failure is retried,
     * which is right for a service whose targets can only be unavailable, never refuse. A subclass
     * dispatching to a target that can say "no" (a gRPC FAILED_PRECONDITION, say) overrides this,
     * or the relay retries a permanent refusal on every poll for the life of the deployment.
     */
    protected boolean isPermanentFailure(Exception e) {
        return false;
    }

    /**
     * Terminal state for a row nothing can deliver. Recorded as cancelled because the poll
     * predicate is what has to stop seeing it and the schema has no separate dead state; the row
     * keeps its payload and retry count, so a parked dispatch is still readable after the fact.
     */
    protected void markParked(java.util.UUID id, Exception cause) {
        tx.executeWithoutResult(status -> outbox.findById(id).ifPresent(e -> {
            e.incrementRetry();
            e.markCancelled();
            outbox.save(e);
            // inside the parking transaction, deliberately: a document whose dispatch died has to
            // say so wherever it says everything else, and a record written afterwards could be
            // lost exactly when it matters
            onParked(e, cause);
        }));
    }

    /**
     * Hook for what a subclass owes its own users when a dispatch is abandoned — typically an
     * audit row naming the action that will now never happen. Runs in {@link #markParked}'s
     * transaction, so anything written here commits with the parking or not at all.
     */
    protected void onParked(OutboxEvent event, Exception cause) {
    }

    protected void markFailed(java.util.UUID id) {
        tx.executeWithoutResult(status -> outbox.findById(id).ifPresent(e -> {
            e.releaseClaim();
            outbox.save(e);
        }));
    }

    /**
     * Cancel path helper — only succeeds if never claimed (mechanics.md M2).
     * Caller must hold the outbox row id (e.g., from the document's submit transaction).
     * Returns true if cancelled directly, false if already claimed (caller must then follow CancelInstance handoff).
     */
    public boolean tryCancelIfNotClaimed(java.util.UUID outboxId) {
        return Boolean.TRUE.equals(
                tx.execute(status -> outbox.cancelIfNotClaimed(outboxId, Instant.now()) == 1));
    }
}
