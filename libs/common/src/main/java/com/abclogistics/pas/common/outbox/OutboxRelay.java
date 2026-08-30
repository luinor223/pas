package com.abclogistics.pas.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

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
 */
public abstract class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final OutboxRelayProperties props;

    @Autowired
    @Lazy
    private OutboxRelay self;

    protected OutboxRelay(OutboxRepository outbox, OutboxRelayProperties props) {
        this.outbox = outbox;
        this.props = props;
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

    @Scheduled(fixedDelayString = "#{@outboxRelayProperties.pollInterval.toMillis()}")
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
        OutboxRelay target = self != null ? self : this;
        List<OutboxEvent> batch = target.pollBatch(staleThreshold);
        if (batch.isEmpty()) {
            return;
        }
        log.debug("Outbox relay polling {} events (staleThreshold={}, lease={})", batch.size(), staleThreshold, props.claimLease());
        for (OutboxEvent event : batch) {
            // Attempt atomic claim — same predicate as poll, so stale claims are reclaimable but cancellable only via separate path
            boolean claimed = target.tryClaim(event.getId(), now, staleThreshold);
            if (!claimed) {
                log.debug("Outbox event {} claim lost (concurrent worker won), skipping", event.getId());
                continue;
            }
            // Reflect claim in the detached batch instance for dispatch context (payload contains idempotency_key)
            event.setClaimedAt(now);
            try {
                dispatch(event);
                target.markPublished(event.getId());
                log.debug("Outbox event {} dispatched to {} and marked published", event.getId(), destination(event));
            } catch (Exception e) {
                log.warn("Outbox dispatch failed for event {} ({}), will retry: {}", event.getId(), event.getEventType(), e.getMessage());
                target.markFailed(event.getId());
            }
        }
    }

    @Transactional
    protected List<OutboxEvent> pollBatch(Instant staleThreshold) {
        return outbox.findUnpublishedForRelay(staleThreshold, Limit.of(props.batchSize()));
    }

    @Transactional
    protected boolean tryClaim(java.util.UUID id, Instant now, Instant staleThreshold) {
        return outbox.claim(id, now, staleThreshold) == 1;
    }

    @Transactional
    protected void markPublished(java.util.UUID id) {
        outbox.findById(id).ifPresent(e -> {
            e.markPublished();
            if (e.getClaimedAt() == null) {
                e.markClaimed();
            }
            outbox.save(e);
        });
    }

    @Transactional
    protected void markFailed(java.util.UUID id) {
        outbox.findById(id).ifPresent(e -> {
            e.setClaimedAt(null);
            e.incrementRetry();
            outbox.save(e);
        });
    }

    /**
     * Cancel path helper — only succeeds if never claimed (mechanics.md M2).
     * Caller must hold the outbox row id (e.g., from the document's submit transaction).
     * Returns true if cancelled directly, false if already claimed (caller must then follow CancelInstance handoff).
     */
    @Transactional
    public boolean tryCancelIfNotClaimed(java.util.UUID outboxId) {
        int updated = outbox.cancelIfNotClaimed(outboxId, Instant.now());
        return updated == 1;
    }
}
