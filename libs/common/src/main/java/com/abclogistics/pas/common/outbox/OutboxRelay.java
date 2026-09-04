package com.abclogistics.pas.common.outbox;

import com.abclogistics.pas.common.correlation.CorrelationSupport;
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
 * Abstract transactional outbox relay. Poll unpublished rows ORDER BY created_at, claim each
 * (0 rows = another worker won, skip), dispatch, stamp published_at on success or release the
 * claim on failure. One relay per service preserves per-document order.
 * <p>
 * Transactions are explicit via {@link TransactionTemplate}, not @Transactional: the steps are
 * self-invoked from {@link #relay()}, which a Spring proxy would not intercept, and each must
 * commit on its own so the claim is visible before dispatch runs.
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

    /** Deliver to the external target. Must be idempotent on the producer side and throw on failure
     *  so the relay retries. */
    protected abstract void dispatch(OutboxEvent event) throws Exception;

    /** Destination (topic or gRPC method) for logging — defaults to the event topic. */
    protected String destination(OutboxEvent event) {
        return event.topic();
    }

    /** Wire shape for all events. Key = aggregate id (one document → one partition → in order).
     *  event_id is the row id, the consumer's dedup key stored in processed_event. */
    protected ProducerRecord<String, String> kafkaRecord(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                event.topic(), event.getAggregateId().toString(), event.getPayload());
        record.headers().add(header("event_id", event.getId().toString()));
        record.headers().add(header("event_type", event.getEventType()));
        record.headers().add(header("document_type", event.getAggregateType()));
        if (event.getCorrelationId() != null) {
            record.headers().add(header(CorrelationSupport.KAFKA_HEADER, event.getCorrelationId()));
        }
        return record;
    }

    private static RecordHeader header(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }

    // property, not a bean-reference SpEL: the properties bean has a generated name
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
            boolean claimed = tryClaim(event.getId(), now, staleThreshold);
            if (!claimed) {
                log.debug("Outbox event {} claim lost (concurrent worker won), skipping", event.getId());
                continue;
            }
            if (event.getCorrelationId() != null) {
                CorrelationSupport.set(event.getCorrelationId());
            }
            try {
                dispatch(event);
                markPublished(event.getId());
                log.debug("Outbox event {} dispatched to {} and marked published", event.getId(), destination(event));
            } catch (Exception e) {
                if (isPermanentFailure(e)) {
                    // permanent refusal: park, else it re-claims every poll for ever
                    log.error("Outbox dispatch permanently refused for event {} ({}) to {} — parking",
                            event.getId(), event.getEventType(), destination(event), e);
                    markParked(event.getId(), e);
                } else {
                    log.warn("Outbox dispatch failed for event {} ({}), will retry: {}", event.getId(), event.getEventType(), e.getMessage());
                    markFailed(event.getId());
                }
            } finally {
                CorrelationSupport.clear();
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
        tx.executeWithoutResult(status -> outbox.markPublished(id, Instant.now()));
    }

    /** True for a refusal that will never succeed (e.g. gRPC FAILED_PRECONDITION). Default false —
     *  retry everything. Override for targets that can say "no", else the relay retries for ever. */
    protected boolean isPermanentFailure(Exception e) {
        return false;
    }

    /** Terminal state for an undeliverable row: cancelled (no separate dead state), payload and
     *  retry count kept. */
    protected void markParked(java.util.UUID id, Exception cause) {
        tx.executeWithoutResult(status -> outbox.findById(id).ifPresent(e -> {
            e.incrementRetry();
            e.markCancelled();
            outbox.save(e);
            onParked(e, cause);
        }));
    }

    /** Hook: what a subclass records when a dispatch is abandoned (e.g. an audit row). Runs in the
     *  parking transaction, so it commits with the parking or not at all. */
    protected void onParked(OutboxEvent event, Exception cause) {
    }

    protected void markFailed(java.util.UUID id) {
        tx.executeWithoutResult(status -> outbox.releaseClaim(id));
    }

    /** Cancel a row only if never claimed. True = cancelled; false = already claimed (caller must
     *  fall back to the CancelInstance handoff). */
    public boolean tryCancelIfNotClaimed(java.util.UUID outboxId) {
        return Boolean.TRUE.equals(
                tx.execute(status -> outbox.cancelIfNotClaimed(outboxId, Instant.now()) == 1));
    }
}
