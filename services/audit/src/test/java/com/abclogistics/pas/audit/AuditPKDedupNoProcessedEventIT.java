package com.abclogistics.pas.audit;

import com.abclogistics.pas.audit.listener.AuditEventListener;
import com.abclogistics.pas.audit.repository.AuditRecordRepository;
import com.abclogistics.pas.common.audit.AuditPayload;
import com.abclogistics.pas.common.events.EventHeaders;
import com.abclogistics.pas.common.events.MalformedEventException;
import com.abclogistics.pas.common.outbox.EventRecords;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * db-audit.md's central claim: {@code id = outbox row id = envelope event_id}, so the primary
 * key <em>is</em> the dedup key and this service needs no {@code processed_event} table. That
 * second half is asserted too — a {@code processed_event} appearing here would mean someone
 * stopped trusting the PK and added a redundant round trip on the hottest write path in the
 * system.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class AuditPKDedupNoProcessedEventIT {

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
    @Autowired AuditRecordRepository records;
    @Autowired JdbcTemplate jdbc;

    // the container is shared across the class and the trail has no delete path
    @BeforeEach
    void empty() {
        jdbc.execute("truncate table audit.audit_record");
    }

    @Test
    void theSameEventIdIsInsertedOnceHoweverOftenItArrives() {
        ConsumerRecord<String, String> record = AuditEventFixtures.recorded(
                AuditEventFixtures.fieldEdit(UUID.randomUUID(), "HD-2026-0001", Instant.now()));
        UUID eventId = UUID.fromString(EventHeaders.of(record, EventHeaders.EVENT_ID));

        listener.onAuditRecorded(record);
        listener.onAuditRecorded(record);
        listener.onAuditRecorded(record);

        assertThat(records.findById(eventId)).isPresent();
        assertThat(records.count()).isEqualTo(1);
    }

    @Test
    void aRedeliveryDoesNotOverwriteWhatWasStored() {
        // ON CONFLICT DO NOTHING, never DO UPDATE
        UUID entityId = UUID.randomUUID();
        AuditPayload original = AuditEventFixtures.statusChange(
                entityId, "CREATE", null, "DRAFT", UUID.randomUUID(), Instant.now());
        ConsumerRecord<String, String> first = AuditEventFixtures.recorded(original);
        UUID eventId = UUID.fromString(EventHeaders.of(first, EventHeaders.EVENT_ID));

        listener.onAuditRecorded(first);
        // the same event id, a different body — a replay after someone tampered with the topic
        listener.onAuditRecorded(EventRecords.withValue(first,
                AuditEventFixtures.MAPPER.writeValueAsString(AuditEventFixtures.statusChange(
                        entityId, "TAMPERED", null, "DRAFT", UUID.randomUUID(), Instant.now()))));

        assertThat(records.findById(eventId).orElseThrow().getAction()).isEqualTo("CREATE");
    }

    @Test
    void twoProducersEventsAboutOneEntityBothLand() {
        // the dedup key is the event, not the entity: a contract accumulates many audit rows
        UUID entityId = UUID.randomUUID();

        listener.onAuditRecorded(AuditEventFixtures.recorded(
                AuditEventFixtures.statusChange(entityId, "CREATE", null, "DRAFT",
                        UUID.randomUUID(), Instant.now())));
        listener.onAuditRecorded(AuditEventFixtures.recorded(
                AuditEventFixtures.statusChange(entityId, "SUBMIT", "DRAFT", "SUBMITTED",
                        UUID.randomUUID(), Instant.now())));

        assertThat(records.count()).isEqualTo(2);
    }

    @Test
    void thePayloadIsStoredWholeIncludingTheUninterpretedChanges() {
        UUID entityId = UUID.randomUUID();
        ConsumerRecord<String, String> record = AuditEventFixtures.recorded(
                AuditEventFixtures.fieldEdit(entityId, "HD-2026-0007", Instant.now()));
        UUID eventId = UUID.fromString(EventHeaders.of(record, EventHeaders.EVENT_ID));

        listener.onAuditRecorded(record);

        var stored = records.findById(eventId).orElseThrow();
        assertThat(stored.getEntityNo()).isEqualTo("HD-2026-0007");
        assertThat(stored.getActorName()).isEqualTo("Nguyen Thi Lan");
        assertThat(stored.getActorDepartment()).isEqualTo("SALES");
        assertThat(stored.getIpAddress()).isEqualTo("10.0.0.1");
        // stored and returned, never interpreted — that is what stops this becoming a god-service
        assertThat(stored.getChanges()).containsKey("paymentTerm");
    }

    @Test
    void aSystemActionIsStoredWithNoActorRatherThanRejected() {
        // schedulers write audit rows too (D14d's time-driven flips); actor_id null is normal
        ConsumerRecord<String, String> record = AuditEventFixtures.recorded(
                AuditEventFixtures.systemAction(UUID.randomUUID(), "ACTIVATE", Instant.now()));
        UUID eventId = UUID.fromString(EventHeaders.of(record, EventHeaders.EVENT_ID));

        listener.onAuditRecorded(record);

        var stored = records.findById(eventId).orElseThrow();
        assertThat(stored.getActorId()).isNull();
        assertThat(stored.getActorName()).isEqualTo("system");
    }

    @Test
    void aValueThatIsNotAnAuditPayloadNeverReachesTheTable() {
        ConsumerRecord<String, String> corrupt = EventRecords.withValue(
                AuditEventFixtures.recorded(
                        AuditEventFixtures.fieldEdit(UUID.randomUUID(), "HD-2026-0001", Instant.now())),
                "{\"sourceService\": ");

        assertThatThrownBy(() -> listener.onAuditRecorded(corrupt))
                .isInstanceOf(MalformedEventException.class);
        assertThat(records.count()).isZero();
    }

    @Test
    void thereIsNoProcessedEventTable() {
        Integer found = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'audit' and table_name = 'processed_event'
                """, Integer.class);
        assertThat(found).isZero();
    }
}
