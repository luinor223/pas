package com.abclogistics.pas.audit;

import com.abclogistics.pas.audit.repository.AuditRecordRepository;
import com.abclogistics.pas.audit.service.AuditIngestService;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * db-audit.md's central claim: {@code id = outbox row id = envelope event_id}, so the primary key
 * <em>is</em> the dedup key and this service needs no {@code processed_event} table. That second
 * half is asserted too — a {@code processed_event} appearing here would mean someone stopped
 * trusting the PK and added a redundant round trip on the hottest write path in the system.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class AuditPKDedupNoProcessedEventTest {

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

    @Autowired AuditIngestService ingest;
    @Autowired AuditRecordRepository records;
    @Autowired JdbcTemplate jdbc;

    @Test
    void theSameEventIdIsInsertedOnceHoweverOftenItArrives() {
        UUID eventId = UUID.randomUUID();
        String envelope = envelopeJson(eventId, "CONTRACT", UUID.randomUUID(), "UPDATE");

        assertThat(ingest.ingest(eventId, envelope)).isTrue();
        assertThat(ingest.ingest(eventId, envelope)).isFalse();
        assertThat(ingest.ingest(eventId, envelope)).isFalse();

        assertThat(records.findById(eventId)).isPresent();
        assertThat(records.count()).isEqualTo(1);
    }

    @Test
    void aRedeliveryDoesNotOverwriteWhatWasStored() {
        // ON CONFLICT DO NOTHING, never DO UPDATE: the trail is append-only, and a replayed
        // record with a mangled payload must not be able to rewrite the original
        UUID eventId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        ingest.ingest(eventId, envelopeJson(eventId, "CONTRACT", entityId, "CREATE"));
        ingest.ingest(eventId, envelopeJson(eventId, "CONTRACT", entityId, "TAMPERED"));

        assertThat(records.findById(eventId).orElseThrow().getAction()).isEqualTo("CREATE");
    }

    @Test
    void thereIsNoProcessedEventTable() {
        Integer found = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'audit' and table_name = 'processed_event'
                """, Integer.class);
        assertThat(found).isZero();
    }

    private static String envelopeJson(UUID eventId, String entityType, UUID entityId, String action) {
        return """
               {"event_id":"%s","event_type":"audit.recorded","occurred_at":"2026-08-31T10:00:00Z",
                "actor_id":null,"actor_name":"system","document_type":"%s","document_id":"%s",
                "payload":{"sourceService":"contract-service","entityType":"%s","entityId":"%s",
                           "entityNo":"HD-2026-0001","action":"%s","actorId":null,
                           "actorName":"system","actorDepartment":null,"beforeStatus":null,
                           "afterStatus":null,"changes":{},"note":null,"ipAddress":null,
                           "occurredAt":"2026-08-31T10:00:00Z"}}
               """.formatted(eventId, entityType, entityId, entityType, entityId, action);
    }
}
