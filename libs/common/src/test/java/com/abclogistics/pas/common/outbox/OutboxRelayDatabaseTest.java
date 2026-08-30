package com.abclogistics.pas.common.outbox;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The M2 claim protocol against a real database.
 *
 * <p>{@link OutboxRelayClaimTest} asserts the queries by reflection and every service's relay test
 * mocks the repository, so nothing before this ran the protocol against Postgres. That gap hid a
 * live defect: the {@code @Transactional} annotations on the relay's own methods were reached by
 * self-invocation and never activated, so the claim — a JPA bulk update, which requires a
 * read-write transaction — could not execute at all.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class OutboxRelayDatabaseTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas_outbox").withUsername("pas").withPassword("pas");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("outbox.relay.enabled", () -> "true");
        registry.add("outbox.relay.claim-lease", () -> "PT60S");
        registry.add("outbox.relay.batch-size", () -> "100");
        // Long enough that the @Scheduled sweep never races a test's explicit pollAndDispatch.
        registry.add("outbox.relay.poll-interval", () -> "PT1H");
    }

    /**
     * Minimal context: the outbox entity, its repository and one concrete relay. The properties
     * bean comes from {@link OutboxCommonConfig}, which this package's component scan picks up.
     */
    @SpringBootApplication
    static class TestApp {
        @Bean
        RecordingRelay recordingRelay(OutboxRepository outbox, OutboxRelayProperties props,
                                      TransactionTemplate tx) {
            return new RecordingRelay(outbox, props, tx);
        }
    }

    /** Succeeds by default; {@code failNext} makes one dispatch throw, as a broker outage would. */
    static class RecordingRelay extends OutboxRelay {
        final List<UUID> dispatched = new CopyOnWriteArrayList<>();
        volatile boolean fail;

        RecordingRelay(OutboxRepository outbox, OutboxRelayProperties props, TransactionTemplate tx) {
            super(outbox, props, tx);
        }

        @Override
        protected void dispatch(OutboxEvent event) {
            if (fail) {
                throw new IllegalStateException("dispatch target unavailable");
            }
            dispatched.add(event.getId());
        }
    }

    @Autowired RecordingRelay relay;
    @Autowired OutboxRepository outbox;
    @Autowired TransactionTemplate tx;

    @Test
    void aPendingRowIsClaimedDispatchedAndMarkedPublished() {
        UUID id = queue("audit.recorded");

        relay.pollAndDispatch();

        assertThat(relay.dispatched).contains(id);
        OutboxEvent row = reload(id);
        assertThat(row.getPublishedAt()).isNotNull();
        assertThat(row.getClaimedAt()).isNotNull();
        assertThat(row.getRetryCount()).isZero();
    }

    @Test
    void aPublishedRowIsNotDispatchedTwice() {
        UUID id = queue("audit.recorded");
        relay.pollAndDispatch();
        relay.dispatched.clear();

        relay.pollAndDispatch();

        assertThat(relay.dispatched).doesNotContain(id);
    }

    @Test
    void aFailedDispatchReleasesTheClaimAndCountsTheAttempt() {
        UUID id = queue("audit.recorded");
        relay.fail = true;
        try {
            relay.pollAndDispatch();
        } finally {
            relay.fail = false;
        }

        OutboxEvent row = reload(id);
        assertThat(row.getPublishedAt()).isNull();
        // cleared, so the next poll reclaims immediately rather than waiting out the lease
        assertThat(row.getClaimedAt()).isNull();
        assertThat(row.getRetryCount()).isEqualTo(1);

        relay.pollAndDispatch();
        assertThat(reload(id).getPublishedAt()).isNotNull();
    }

    @Test
    void rowsAreDispatchedInCommitOrder() {
        // ORDER BY created_at with one publisher per service is what preserves a document's
        // commit order on the way to the broker (M2).
        List<UUID> queued = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            queued.add(queue("audit.recorded"));
        }

        relay.pollAndDispatch();

        assertThat(relay.dispatched).containsSubsequence(queued.toArray(UUID[]::new));
    }

    @Test
    void aCancelledRowIsNeverDispatched() {
        UUID id = queue("workflow.start_requested");
        assertThat(relay.tryCancelIfNotClaimed(id)).isTrue();

        relay.pollAndDispatch();

        assertThat(relay.dispatched).doesNotContain(id);
        assertThat(reload(id).getPublishedAt()).isNull();
    }

    @Test
    void aClaimedRowIsNotCancellable() {
        // M2: the lease has no fencing token, so a claim — fresh or stale — is never cancelled.
        UUID id = queue("workflow.start_requested");
        relay.fail = true;
        try {
            relay.pollAndDispatch();   // claims it, fails, and releases the claim
        } finally {
            relay.fail = false;
        }
        tx.executeWithoutResult(s -> outbox.claim(id, java.time.Instant.now(),
                java.time.Instant.now().minusSeconds(60)));

        assertThat(relay.tryCancelIfNotClaimed(id)).isFalse();
        assertThat(reload(id).getCancelledAt()).isNull();
    }

    // --- helpers ----------------------------------------------------------------------------

    private UUID queue(String eventType) {
        OutboxEvent saved = tx.execute(s -> outbox.save(
                OutboxEvent.event(eventType, "CONTRACT", UUID.randomUUID(), "{}")));
        return saved.getId();
    }

    private OutboxEvent reload(UUID id) {
        return tx.execute(s -> outbox.findById(id).orElseThrow());
    }
}
