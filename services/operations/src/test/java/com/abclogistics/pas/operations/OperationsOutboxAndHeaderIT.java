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

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Autowired ObjectMapper objectMapper;

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
    void auditRecordedWrittenToOutboxAndPeriodLockedHasCorrectHeaders() {
        String periodCode = "2026-08";
        try { periodService.create(periodCode); } catch (Exception ignored) {}
        long beforeLockOutbox = outbox.count();
        periodService.lock(periodCode);
        // afterCommit is synchronous on commit thread (PeriodService uses TransactionSynchronization.afterCommit), no sleep needed

        // audit via outbox
        boolean hasAudit = outbox.findAll().stream().anyMatch(e -> e.getEventType().equals("audit.recorded") && e.getPayload().contains("period.locked"));
        assertThat(hasAudit).isTrue();
        assertThat(outbox.count()).isGreaterThan(beforeLockOutbox);

        // verify period_locked uses the same bare-payload wire contract as relayed events
        // TestGrpcConfig mocks KafkaTemplate.send(ProducerRecord) to complete immediately; afterCommit is synchronous so timeout 500 is deterministic
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<ProducerRecord<String,String>> captor = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, org.mockito.Mockito.timeout(500).atLeastOnce()).send(captor.capture());
        boolean found = captor.getAllValues().stream().anyMatch(rec -> {
            boolean isEvent = "pas.events".equals(rec.topic()) && periodCode.equals(rec.key());
            boolean hasType = rec.headers().headers("event_type").iterator().hasNext()
                    && new String(rec.headers().headers("event_type").iterator().next().value()).equals("operations.period_locked");
            boolean hasDoc = rec.headers().headers("document_type").iterator().hasNext()
                    && new String(rec.headers().headers("document_type").iterator().next().value()).equals("OPERATION_PERIOD");
            boolean hasEventId = rec.headers().headers("event_id").iterator().hasNext();
            try {
                var payload = objectMapper.readTree(rec.value());
                boolean barePayload = payload.has("period_code") && payload.has("document_no")
                        && !payload.has("event_id") && !payload.has("payload");
                return isEvent && hasType && hasDoc && hasEventId && barePayload;
            } catch (Exception ignored) {
                return false;
            }
        });
        assertThat(found).withFailMessage("period_locked not sent with mandatory headers and a bare payload").isTrue();
    }
}
