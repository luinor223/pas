package com.abclogistics.pas.common.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies mechanics.md M2 relay claim protocol without requiring a database:
 * poll {@code ORDER BY created_at}, claim {@code WHERE ... OR claimed_at < now() - N},
 * and that the relay respects the same predicate on poll and claim.
 * Session 0 — Foundation.
 */
class OutboxRelayClaimTest {

    @Test
    void pollQueryOrdersByCreatedAtAndIncludesStalePredicate() throws Exception {
        Method m = OutboxRepository.class.getMethod("findUnpublishedForRelay", Instant.class, org.springframework.data.domain.Limit.class);
        Query q = m.getAnnotation(Query.class);
        assertThat(q).isNotNull();
        String value = q.value();
        // must order by created_at
        assertThat(value).containsIgnoringCase("order by")
                .containsIgnoringCase("createdAt")
                .containsIgnoringCase("asc");
        // must filter stale via claimedAt < :staleThreshold
        assertThat(value).contains("claimedAt")
                .contains("< :staleThreshold");
        // must exclude published and cancelled
        assertThat(value).contains("publishedAt is null")
                .contains("cancelledAt is null");
        // must include the OR stale clause
        assertThat(value).contains("claimedAt is null or");
    }

    @Test
    void claimQueryUsesSamePredicateAsPoll() throws Exception {
        Method m = OutboxRepository.class.getMethod("claim", UUID.class, Instant.class, Instant.class);
        Query q = m.getAnnotation(Query.class);
        assertThat(q).isNotNull();
        String value = q.value();
        assertThat(value).containsIgnoringCase("update")
                .contains("claimedAt = :now")
                .contains("publishedAt is null")
                .contains("cancelledAt is null")
                .contains("claimedAt is null or")
                .contains("< :staleThreshold");
    }

    @Test
    void cancelQueryHasNoStalenessClause_onlyClaimedAtIsNull() throws Exception {
        Method m = OutboxRepository.class.getMethod("cancelIfNotClaimed", UUID.class, Instant.class);
        Query q = m.getAnnotation(Query.class);
        assertThat(q).isNotNull();
        String value = q.value();
        assertThat(value).contains("cancelledAt = :now")
                .contains("claimedAt is null")
                .contains("publishedAt is null")
                .contains("cancelledAt is null");
        // must NOT contain staleThreshold
        assertThat(value).doesNotContain("staleThreshold");
        assertThat(value).doesNotContain("<");
    }

    @Test
    void relayOrdersByCreatedAt_inMemorySimulation() {
        // Simulate the ordering constraint the relay relies on for commit-order preservation
        OutboxEvent e1 = eventWithCreatedAt(Instant.parse("2026-01-01T10:00:00Z"));
        OutboxEvent e2 = eventWithCreatedAt(Instant.parse("2026-01-01T09:00:00Z"));
        OutboxEvent e3 = eventWithCreatedAt(Instant.parse("2026-01-01T11:00:00Z"));
        List<OutboxEvent> batch = List.of(e1, e2, e3).stream()
                .sorted(Comparator.comparing(OutboxEvent::getCreatedAt))
                .toList();
        assertThat(batch.get(0).getId()).isEqualTo(e2.getId());
        assertThat(batch.get(1).getId()).isEqualTo(e1.getId());
        assertThat(batch.get(2).getId()).isEqualTo(e3.getId());
    }

    @Test
    void outboxEventLifecycle_PendingToPublishedAndStaleHandling() {
        Instant now = Instant.parse("2026-01-01T12:00:00Z");
        Instant staleThreshold = now.minusSeconds(60);

        OutboxEvent pending = eventWithCreatedAt(now.minusSeconds(100));
        assertThat(pending.getClaimedAt()).isNull();
        assertThat(pending.getPublishedAt()).isNull();

        // claim
        pending.setClaimedAt(now);
        assertThat(pending.getClaimedAt()).isEqualTo(now);

        // stale check: fresh claim should not be < threshold
        OutboxEvent fresh = eventWithCreatedAt(now.minusSeconds(50));
        fresh.setClaimedAt(now.minusSeconds(10));
        assertThat(fresh.getClaimedAt().isBefore(staleThreshold)).isFalse();

        OutboxEvent stale = eventWithCreatedAt(now.minusSeconds(50));
        stale.setClaimedAt(now.minusSeconds(120));
        assertThat(stale.getClaimedAt().isBefore(staleThreshold)).isTrue();

        // publish
        pending.markPublished();
        assertThat(pending.getPublishedAt()).isNotNull();

        // cancel only if never claimed
        OutboxEvent toCancel = eventWithCreatedAt(now.minusSeconds(10));
        assertThat(toCancel.getClaimedAt()).isNull();
        toCancel.markCancelled();
        assertThat(toCancel.getCancelledAt()).isNotNull();
    }

    @Test
    void outboxRelayPropertiesDefaults_matchMechanics() {
        OutboxRelayProperties props = new OutboxRelayProperties();
        assertThat(props.claimLease().toSeconds()).isEqualTo(60);
        assertThat(props.batchSize()).isEqualTo(100);
        assertThat(props.pollInterval().toSeconds()).isEqualTo(5);
        assertThat(props.enabled()).isTrue();
    }

    private OutboxEvent eventWithCreatedAt(Instant createdAt) {
        OutboxEvent e = OutboxEvent.event("audit.recorded", "test", UUID.randomUUID(), "{\"k\":\"v\"}");
        e.setCreatedAt(createdAt);
        return e;
    }
}
