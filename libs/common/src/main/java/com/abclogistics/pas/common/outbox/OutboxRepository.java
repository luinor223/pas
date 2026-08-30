package com.abclogistics.pas.common.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Poll for relay — oldest unpublished rows, including stale claims reclaimable after the lease.
     * Matches mechanics.md M2: WHERE published_at IS NULL AND cancelled_at IS NULL
     * AND (claimed_at IS NULL OR claimed_at < now() - interval 'N seconds') ORDER BY created_at.
     * Row-locked with SKIP LOCKED so concurrent relays don't collide; callers must pass
     * {@code staleThreshold = now() - lease}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")) // SKIP LOCKED
    @Query("select o from OutboxEvent o where o.publishedAt is null and o.cancelledAt is null and (o.claimedAt is null or o.claimedAt < :staleThreshold) order by o.createdAt asc")
    List<OutboxEvent> findUnpublishedForRelay(@Param("staleThreshold") Instant staleThreshold, Limit limit);

    /**
     * Claim a single row — must use the same staleness predicate as the poll query.
     * {@code UPDATE ... SET claimed_at = now() WHERE id = ? AND published_at IS NULL AND cancelled_at IS NULL
     * AND (claimed_at IS NULL OR claimed_at < now() - N)} — 0 rows means already claimed/published/cancelled.
     */
    @Modifying
    @Query("update OutboxEvent o set o.claimedAt = :now where o.id = :id and o.publishedAt is null and o.cancelledAt is null and (o.claimedAt is null or o.claimedAt < :staleThreshold)")
    int claim(@Param("id") UUID id, @Param("now") Instant now, @Param("staleThreshold") Instant staleThreshold);

    /**
     * Cancel only if never claimed — no staleness clause (M2: only claimed_at IS NULL is ever cancellable).
     * Returns 1 if cancelled, 0 if already claimed/published/cancelled.
     */
    @Modifying
    @Query("update OutboxEvent o set o.cancelledAt = :now where o.id = :id and o.claimedAt is null and o.publishedAt is null and o.cancelledAt is null")
    int cancelIfNotClaimed(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * The document's dispatch intents of one type, newest first, cancelled rows excluded — step 0
     * of the M2 cancel handoff. A resubmitted document accumulates rows (CTR-04's revise and every
     * REVISION_REQUESTED round trip mint a new one), and only the newest can still be in flight.
     */
    @Query("select o from OutboxEvent o where o.aggregateId = :aggregateId and o.eventType = :eventType and o.cancelledAt is null order by o.createdAt desc")
    List<OutboxEvent> findForAggregate(@Param("aggregateId") UUID aggregateId,
                                       @Param("eventType") String eventType, Limit limit);

    /** Legacy alias kept for compatibility — delegates to stale-aware poll with far-past threshold. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select o from OutboxEvent o where o.publishedAt is null and o.cancelledAt is null order by o.createdAt asc")
    List<OutboxEvent> claimUnpublished(Limit limit);
}
