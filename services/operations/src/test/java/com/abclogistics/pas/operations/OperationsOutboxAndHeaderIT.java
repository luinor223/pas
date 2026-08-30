package com.abclogistics.pas.operations;

import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.operations.service.PeriodService;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * P0-8: verify audit → outbox → pas.audit claim SQL and period_locked headers.
 * Uses mocked KafkaTemplate (TestGrpcConfig) but verifies headers/topic/key.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestGrpcConfig.class)
class OperationsOutboxAndHeaderIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16").withDatabaseName("pas").withUsername("pas").withPassword("pas");
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("outbox.relay.enabled", () -> "false");
        r.add("contract.grpc.host", () -> "localhost");
        r.add("contract.grpc.port", () -> "50052");
        r.add("pricing.grpc.host", () -> "localhost");
        r.add("pricing.grpc.port", () -> "50053");
    }

    @Autowired PeriodService periodService;
    @Autowired OutboxRepository outbox;
    @Autowired KafkaTemplate<String, String> kafka;

    @BeforeEach
    void setAuth() {
        var user = new AuthenticatedUser(UUID.randomUUID(), "ops", "Ops Officer", "OPERATIONS", List.of("OPS_OFFICER"));
        var authorities = List.of(
                new SimpleGrantedAuthority("volume:read"),
                new SimpleGrantedAuthority("volume:write"),
                new SimpleGrantedAuthority("volume:lock_period"),
                new SimpleGrantedAuthority("volume:edit_locked")
        );
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
    }

    @Test
    void auditRecordedWrittenToOutboxAndPeriodLockedHasCorrectHeaders() throws Exception {
        String periodCode = "2026-08";
        try { periodService.create(periodCode); } catch (Exception ignored) {}
        // wait a bit for any async afterCommit to complete before count check
        Thread.sleep(200);
        long beforeLockOutbox = outbox.count();
        periodService.lock(periodCode);
        // afterCommit runs inside same thread after TX commit, but give it a moment for async mock
        Thread.sleep(300);

        // audit via outbox
        boolean hasAudit = outbox.findAll().stream().anyMatch(e -> e.getEventType().equals("audit.recorded") && e.getPayload().contains("period.locked"));
        assertThat(hasAudit).isTrue();
        assertThat(outbox.count()).isGreaterThan(beforeLockOutbox);

        // verify period_locked was published to pas.events with key=period_code and headers event_type/document_type
        // TestGrpcConfig mocks KafkaTemplate.send(ProducerRecord) to complete immediately
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<ProducerRecord<String,String>> captor = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, atLeastOnce()).send(captor.capture());
        boolean found = captor.getAllValues().stream().anyMatch(rec -> {
            boolean isEvent = "pas.events".equals(rec.topic()) && periodCode.equals(rec.key());
            boolean hasType = rec.headers().headers("event_type").iterator().hasNext()
                    && new String(rec.headers().headers("event_type").iterator().next().value()).equals("operations.period_locked");
            boolean hasDoc = rec.headers().headers("document_type").iterator().hasNext()
                    && new String(rec.headers().headers("document_type").iterator().next().value()).equals("OPERATION_PERIOD");
            // envelope should contain event_id etc.
            boolean hasEnvelope = rec.value() != null && rec.value().contains("event_id") && rec.value().contains("period_code");
            return isEvent && hasType && hasDoc && hasEnvelope;
        });
        assertThat(found).withFailMessage("period_locked not sent with correct topic/key/headers/envelope").isTrue();
    }
}
