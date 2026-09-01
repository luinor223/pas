package com.abclogistics.pas.audit;

import com.abclogistics.pas.audit.domain.AuditRecord;
import com.abclogistics.pas.audit.listener.AuditEventListener;
import com.abclogistics.pas.audit.service.AuditQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin UC "Tra cứu audit log" (seq-02) against real SQL.
 *
 * <p>{@link AuditRecordSearchTest} mocks the repository, so it can only prove the service passes
 * its arguments down — it cannot see a predicate ORed instead of ANDed, an exclusive date bound, a
 * missing {@code order by}, or a page count that double-counts a join. Those are the ways this
 * query actually breaks, and all of them need a database.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class AuditRecordSearchIT {

    private static final Instant JAN = Instant.parse("2026-01-15T09:00:00Z");
    private static final Instant FEB = Instant.parse("2026-02-15T09:00:00Z");
    private static final Instant MAR = Instant.parse("2026-03-15T09:00:00Z");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas_audit").withUsername("pas").withPassword("pas");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("audit.kafka.listener-enabled", () -> "false");
    }

    @Autowired AuditEventListener listener;
    @Autowired AuditQueryService audit;
    @Autowired JdbcTemplate jdbc;

    private final UUID lan = UUID.randomUUID();
    private final UUID minh = UUID.randomUUID();
    private final UUID contractId = UUID.randomUUID();
    private final UUID statementId = UUID.randomUUID();

    @BeforeEach
    void seed() {
        jdbc.execute("truncate table audit.audit_record");
        record("contract-service", "CONTRACT", contractId, "HD-2026-0001", "CREATE", lan, JAN);
        record("contract-service", "CONTRACT", contractId, "HD-2026-0001", "APPROVE", minh, FEB);
        record("contract-service", "CONTRACT", contractId, "HD-2026-0001", "REJECT", lan, MAR);
        record("billing-service", "PAYMENT_STATEMENT", statementId, "PMT-2026-0001", "CREATE", lan, FEB);
    }

    @Test
    void noFiltersReturnsEverythingNewestFirst() {
        Page<AuditRecord> page = audit.search(null, null, null, null, null, null, null, first());

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).extracting(AuditRecord::getOccurredAt)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void actorAndDateBoundTheSearchTogetherRatherThanSeparately() {
        // "what did Lan do in February" — an OR here would return Minh's February row and Lan's
        // January and March ones, which is every row in the table
        Page<AuditRecord> page = audit.search(null, null, lan, null, null,
                Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-02-28T23:59:59Z"), first());

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getEntityNo()).isEqualTo("PMT-2026-0001");
    }

    @Test
    void theDateBoundsAreInclusive() {
        // from/to come off a date picker: a user picking 15 Jan to 15 Mar means those two days are
        // in, and an exclusive bound silently drops the edge rows they were looking for
        Page<AuditRecord> page = audit.search(null, null, null, null, null, JAN, MAR, first());

        assertThat(page.getTotalElements()).isEqualTo(4);
    }

    @Test
    void aServiceFilterSeparatesTheProducers() {
        Page<AuditRecord> page = audit.search(null, null, null, "billing-service", null, null, null, first());

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getEntityType()).isEqualTo("PAYMENT_STATEMENT");
    }

    @Test
    void everyRejectInAPeriod() {
        // the second cross-entity axis from seq-02: an action across all entities and actors
        Page<AuditRecord> page = audit.search(null, null, null, null, "REJECT", null, null, first());

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getActorId()).isEqualTo(lan);
    }

    @Test
    void anEntityNoNarrowsToOneDocumentsTrail() {
        Page<AuditRecord> page = audit.search(null, "HD-2026-0001", null, null, null, null, null, first());

        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void aFilterMatchingNothingReturnsAnEmptyPageNotEverything() {
        // the `:x is null or ...` idiom fails open if a parameter is bound wrong, and "no matches"
        // silently becoming "all rows" is the worst possible failure for an audit search
        Page<AuditRecord> page = audit.search(null, null, UUID.randomUUID(), null, null, null, null, first());

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void theTotalCountsMatchesNotThePage() {
        Page<AuditRecord> page = audit.search(null, null, null, null, null, null, null, PageRequest.of(0, 2));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    void thesecondPageContinuesWhereTheFirstStopped() {
        Page<AuditRecord> firstPage = audit.search(null, null, null, null, null, null, null, PageRequest.of(0, 2));
        Page<AuditRecord> secondPage = audit.search(null, null, null, null, null, null, null, PageRequest.of(1, 2));

        assertThat(secondPage.getContent()).doesNotContainAnyElementsOf(firstPage.getContent());
    }

    @Test
    void perEntityHistoryIsScopedToOneEntityAndNewestFirst() {
        // the gRPC path's query, which must never widen into a cross-entity scan
        Page<AuditRecord> page = audit.forEntity("CONTRACT", contractId, first());

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getContent()).extracting(AuditRecord::getAction)
                .containsExactly("REJECT", "APPROVE", "CREATE");
    }

    @Test
    void perEntityHistoryDistinguishesTypesThatShareAnId() {
        // entity_id alone is not unique across contexts; the type is half the key
        assertThat(audit.forEntity("PAYMENT_STATEMENT", contractId, first()).getContent()).isEmpty();
    }

    private void record(String service, String entityType, UUID entityId, String entityNo,
                        String action, UUID actorId, Instant at) {
        listener.onAuditRecorded(AuditEventFixtures.recorded(
                AuditEventFixtures.by(service, entityType, entityId, entityNo, action, actorId, at)));
    }

    private static PageRequest first() {
        return PageRequest.of(0, 20);
    }
}
