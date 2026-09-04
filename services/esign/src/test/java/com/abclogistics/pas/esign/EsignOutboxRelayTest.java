package com.abclogistics.pas.esign;

import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRelayProperties;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.esign.domain.SigningSession;
import com.abclogistics.pas.esign.domain.SigningSession.SessionStatus;
import com.abclogistics.pas.esign.client.MockProviderClient;
import com.abclogistics.pas.esign.outbox.EsignOutboxRelay;
import com.abclogistics.pas.esign.repository.SigningSessionRepository;
import com.abclogistics.pas.esign.service.SigningSessionService;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The dual-sink routing on the esign relay: esign.provider_send goes to the provider over HTTP with
 * the shared retry/park machinery, everything else goes to Kafka. A permanent refusal (4xx) or an
 * exhausted send parks and flips the session to FAILED; a transient failure is released for retry.
 */
class EsignOutboxRelayTest {

    private static final int MAX_ATTEMPTS = 3;

    private OutboxRepository outbox;
    private KafkaTemplate<String, String> kafka;
    private MockProviderClient provider;
    private SigningSessionService sessions;
    private SigningSessionRepository sessionRepo;
    private EsignOutboxRelay relay;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outbox = mock(OutboxRepository.class);
        kafka = mock(KafkaTemplate.class);
        provider = mock(MockProviderClient.class);
        sessions = mock(SigningSessionService.class);
        sessionRepo = mock(SigningSessionRepository.class);
        relay = new EsignOutboxRelay(outbox, new OutboxRelayProperties(), directTransactions(), kafka,
                provider, sessions, sessionRepo, "http://esign/callbacks/esign", MAX_ATTEMPTS);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void aProviderSendReachesTheProviderAndMarksTheSessionSent() {
        SigningSession session = pending();
        OutboxEvent event = queued(providerSend(session));
        when(sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        when(provider.sendForSigning(anyString(), any(), any(), any(), anyString())).thenReturn("MOCK-1");

        relay.pollAndDispatch();

        verify(sessions).markSent(session.getId(), "MOCK-1", 1);
        verify(kafka, never()).send(any(ProducerRecord.class));
        assertThat(event.getPublishedAt()).isNotNull();
    }

    @Test
    void aTransientProviderFailureReleasesTheClaimForRetry() {
        SigningSession session = pending();
        OutboxEvent event = queued(providerSend(session));
        when(sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        when(provider.sendForSigning(anyString(), any(), any(), any(), anyString()))
                .thenThrow(new ResourceAccessException("provider unreachable"));

        relay.pollAndDispatch();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getCancelledAt()).isNull();
        assertThat(event.getRetryCount()).isEqualTo(1);
        verify(sessions, never()).markSent(any(), any(), anyInt());
        verify(sessions, never()).failSend(any(), anyInt(), any());
    }

    @Test
    void aFourxxRefusalParksTheRowAndFailsTheSession() {
        SigningSession session = pending();
        OutboxEvent event = queued(providerSend(session));
        when(sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        when(provider.sendForSigning(anyString(), any(), any(), any(), anyString()))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", null, null, null));

        relay.pollAndDispatch();

        assertThat(event.getCancelledAt()).isNotNull();   // parked, not retried
        assertThat(event.getPublishedAt()).isNull();
        verify(sessions).failSend(eq(session.getId()), eq(1), anyString());
    }

    @Test
    void anExhaustedSendParksTheRowAndFailsTheSession() {
        SigningSession session = pending();
        OutboxEvent event = providerSend(session);
        event.incrementRetry();
        event.incrementRetry();   // retryCount = 2, so this is attempt 3 = MAX_ATTEMPTS
        queued(event);
        when(sessionRepo.findById(session.getId())).thenReturn(Optional.of(session));
        when(provider.sendForSigning(anyString(), any(), any(), any(), anyString()))
                .thenThrow(new ResourceAccessException("still unreachable"));

        relay.pollAndDispatch();

        assertThat(event.getCancelledAt()).isNotNull();
        verify(sessions).failSend(eq(session.getId()), eq(3), anyString());
    }

    @Test
    void aCompletionEventIsPublishedToKafka() {
        OutboxEvent event = queued(OutboxEvent.event("esign.session_completed", "CONTRACT",
                UUID.randomUUID(), "{\"result\":\"SIGNED\"}"));

        relay.pollAndDispatch();

        verify(kafka).send(any(ProducerRecord.class));
        assertThat(event.getPublishedAt()).isNotNull();
        verifyNoInteractions(provider, sessionRepo);
    }

    // --- helpers -----------------------------------------------------------------------------

    private static SigningSession pending() {
        SigningSession s = SigningSession.create("CONTRACT", UUID.randomUUID(), "HD-1", "ACME",
                "Signer", "s@acme.vn", UUID.randomUUID(), UUID.randomUUID(), "Req");
        s.setSessionNo("SIG-1");
        s.setStatus(SessionStatus.PENDING_SEND);
        return s;
    }

    private static OutboxEvent providerSend(SigningSession session) {
        return OutboxEvent.event(SigningSessionService.PROVIDER_SEND, "CONTRACT", session.getId(),
                "{\"session_no\":\"" + session.getSessionNo() + "\"}");
    }

    private OutboxEvent queued(OutboxEvent... events) {
        when(outbox.findUnpublishedForRelay(any(Instant.class), any(Limit.class))).thenReturn(List.of(events));
        when(outbox.claim(any(UUID.class), any(Instant.class), any(Instant.class))).thenReturn(1);
        for (OutboxEvent event : events) {
            when(outbox.findById(event.getId())).thenReturn(Optional.of(event));
            when(outbox.markPublished(eq(event.getId()), any(Instant.class))).thenAnswer(call -> {
                event.markPublished();
                return 1;
            });
            when(outbox.releaseClaim(event.getId())).thenAnswer(call -> {
                event.releaseClaim();
                return 1;
            });
        }
        return events[0];
    }

    private static TransactionTemplate directTransactions() {
        return new TransactionTemplate(new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { }
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { }
        });
    }
}
