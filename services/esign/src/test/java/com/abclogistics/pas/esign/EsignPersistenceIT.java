package com.abclogistics.pas.esign;

import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.esign.domain.SigningSession;
import com.abclogistics.pas.esign.domain.SigningSession.SessionStatus;
import com.abclogistics.pas.esign.repository.SigningSessionRepository;
import com.abclogistics.pas.esign.repository.StatusHistoryRepository;
import com.abclogistics.pas.esign.scheduler.EsignDispatchScheduler;
import com.abclogistics.pas.esign.service.SigningSessionService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The invariants only a real Postgres enforces: the migrations apply and validate, the two-constraint
 * idempotency (unique key plus the partial-unique active index) actually rejects the races the service
 * pre-checks cannot see, and V3 lets a system-driven send store a null requester.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class EsignPersistenceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("pas_esign").withUsername("pas").withPassword("pas");

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
        registry.add("spring.grpc.server.port", () -> 0);
        registry.add("outbox.relay.enabled", () -> "false");
    }

    // The dispatch scheduler would otherwise poll our PENDING_SEND rows and try to reach the provider.
    @MockitoBean
    EsignDispatchScheduler dispatchScheduler;

    @Autowired SigningSessionRepository sessions;
    @Autowired StatusHistoryRepository history;
    @Autowired OutboxRepository outbox;
    @Autowired SigningSessionService service;

    @Test
    void migrationsApplyAndTheSchemaValidates() {
        // Reaching a queryable repository means Flyway V1..V3 ran and Hibernate validate passed against
        // the resulting schema; the context would not have started otherwise.
        assertThat(sessions.findBySessionNo("no-such-session")).isEmpty();
    }

    @Test
    void theIdempotencyKeyIsUniqueAtTheDatabase() {
        UUID key = UUID.randomUUID();
        sessions.saveAndFlush(row("SIG-1001", "CONTRACT", UUID.randomUUID(), key, SessionStatus.SIGNED));

        assertThatThrownBy(() ->
                sessions.saveAndFlush(row("SIG-1002", "CONTRACT", UUID.randomUUID(), key, SessionStatus.SIGNED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOneActiveSessionPerDocumentIsAllowed() {
        UUID documentId = UUID.randomUUID();
        SigningSession active = sessions.saveAndFlush(
                row("SIG-2001", "CONTRACT", documentId, UUID.randomUUID(), SessionStatus.PENDING_SEND));

        // a second active session for the same document is rejected by the partial-unique index
        assertThatThrownBy(() ->
                sessions.saveAndFlush(row("SIG-2002", "CONTRACT", documentId, UUID.randomUUID(), SessionStatus.SIGNING)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // once the first leaves the active set, a new session for the same document is allowed again
        active.setStatus(SessionStatus.SIGNED);
        sessions.saveAndFlush(active);
        sessions.saveAndFlush(row("SIG-2003", "CONTRACT", documentId, UUID.randomUUID(), SessionStatus.PENDING_SEND));

        assertThat(sessions.findActiveByDocument("CONTRACT", documentId)).hasSize(1);
    }

    @Test
    void requestedByMayBeNullForASystemDrivenSend() {
        SigningSession s = SigningSession.create("PAYMENT_STATEMENT", UUID.randomUUID(), "PMT-1",
                "ACME", "Signer", "s@acme.vn", UUID.randomUUID(), null, null);
        s.setSessionNo("SIG-3001");

        SigningSession saved = sessions.saveAndFlush(s);

        assertThat(sessions.findById(saved.getId())).get()
                .satisfies(x -> assertThat(x.getRequestedBy()).isNull());
    }

    @Test
    void aProviderCallbackDrivesTheSessionToSignedWithHistoryAndACompletionEvent() {
        SigningSession created = service.createSession("CONTRACT", UUID.randomUUID(), "HD-1", "ACME",
                "Signer", "s@acme.vn", UUID.randomUUID(), UUID.randomUUID(), "Req");

        service.handleCallback(created.getSessionNo(), "MOCK-abc", "SIGNED", null);

        assertThat(sessions.findBySessionNo(created.getSessionNo())).get()
                .satisfies(s -> assertThat(s.getStatus()).isEqualTo(SessionStatus.SIGNED));
        assertThat(history.count()).isGreaterThanOrEqualTo(2);   // opening PENDING_SEND + the SIGNED edge
        assertThat(outbox.findAll()).anySatisfy(e ->
                assertThat(e.getEventType()).isEqualTo("esign.session_completed"));
    }

    private static SigningSession row(String sessionNo, String docType, UUID documentId,
                                      UUID idempotencyKey, SessionStatus status) {
        SigningSession s = SigningSession.create(docType, documentId, "DOC-1", "ACME",
                "Signer", "s@acme.vn", idempotencyKey, UUID.randomUUID(), "Req");
        s.setSessionNo(sessionNo);
        s.setStatus(status);
        return s;
    }
}
