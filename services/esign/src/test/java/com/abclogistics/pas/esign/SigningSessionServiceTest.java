package com.abclogistics.pas.esign;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.esign.domain.SigningSession;
import com.abclogistics.pas.esign.domain.SigningSession.SessionStatus;
import com.abclogistics.pas.esign.repository.SigningCallbackLogRepository;
import com.abclogistics.pas.esign.repository.SigningSessionRepository;
import com.abclogistics.pas.esign.repository.StatusHistoryRepository;
import com.abclogistics.pas.esign.service.MockProviderClient;
import com.abclogistics.pas.esign.service.SigningSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Session lifecycle behaviour, no Spring context and no Docker. The provider send, the version-guarded
 * callback, and cancel are the transitions worth pinning: each one flips status, appends history, and
 * (on a terminal edge) emits the completion event that billing and notification consume.
 */
class SigningSessionServiceTest {

    private SigningSessionRepository sessions;
    private SigningCallbackLogRepository callbackLog;
    private StatusHistoryRepository history;
    private OutboxRepository outbox;
    private AuditRecorder audit;
    private MockProviderClient provider;
    private SigningSessionService service;

    @BeforeEach
    void setUp() {
        sessions = mock(SigningSessionRepository.class);
        callbackLog = mock(SigningCallbackLogRepository.class);
        history = mock(StatusHistoryRepository.class);
        outbox = mock(OutboxRepository.class);
        audit = mock(AuditRecorder.class);
        provider = mock(MockProviderClient.class);
        service = new SigningSessionService(sessions, callbackLog, history, outbox, audit, provider,
                new ObjectMapper());
        when(sessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // --- create ------------------------------------------------------------------------------

    @Test
    void createReturnsTheExistingSessionForARepeatedKey() {
        SigningSession existing = session(SessionStatus.SIGNING);
        when(sessions.findByIdempotencyKey(existing.getIdempotencyKey())).thenReturn(Optional.of(existing));

        SigningSession result = service.createSession("CONTRACT", UUID.randomUUID(), "HD-1", "ACME",
                "Signer", "s@acme.vn", existing.getIdempotencyKey(), UUID.randomUUID(), "Req");

        assertThat(result).isSameAs(existing);
        verify(sessions, never()).save(any());
        verify(sessions, never()).existsByDocumentTypeCodeAndDocumentIdAndStatusIn(anyString(), any(), any());
    }

    @Test
    void createRejectsASecondActiveSendForTheSameDocument() {
        UUID documentId = UUID.randomUUID();
        when(sessions.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(sessions.existsByDocumentTypeCodeAndDocumentIdAndStatusIn("CONTRACT", documentId,
                List.of(SessionStatus.PENDING_SEND, SessionStatus.SIGNING))).thenReturn(true);

        assertThatThrownBy(() -> service.createSession("CONTRACT", documentId, "HD-1", "ACME",
                "Signer", "s@acme.vn", UUID.randomUUID(), UUID.randomUUID(), "Req"))
                .isInstanceOf(FailedPreconditionException.class);
    }

    @Test
    void createPersistsAPendingSessionWithAnAllocatedNumberAndOpeningHistory() {
        when(sessions.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(sessions.existsByDocumentTypeCodeAndDocumentIdAndStatusIn(anyString(), any(), any()))
                .thenReturn(false);
        when(sessions.nextSessionNoSeq()).thenReturn(42L);

        SigningSession created = service.createSession("CONTRACT", UUID.randomUUID(), "HD-1", "ACME",
                "Signer", "s@acme.vn", UUID.randomUUID(), UUID.randomUUID(), "Req");

        assertThat(created.getSessionNo()).isEqualTo("SIG-42");
        assertThat(created.getStatus()).isEqualTo(SessionStatus.PENDING_SEND);
        assertThat(created.getStatusHistory()).hasSize(1);
        assertThat(created.getStatusHistory().get(0).getToStatus()).isEqualTo("PENDING_SEND");
    }

    // --- send --------------------------------------------------------------------------------

    @Test
    void aSuccessfulSendMovesToSigningAndRecordsTheProviderRef() {
        SigningSession session = session(SessionStatus.PENDING_SEND);
        when(provider.sendForSigning(anyString(), any(), any(), any(), anyString())).thenReturn("MOCK-abc");

        service.sendToProvider(session);

        assertThat(session.getStatus()).isEqualTo(SessionStatus.SIGNING);
        assertThat(session.getProviderRef()).isEqualTo("MOCK-abc");
        verify(outbox, never()).save(any());
    }

    @Test
    void aSendThatStillHasRetriesLeftIsLeftPendingNotFailed() {
        SigningSession session = session(SessionStatus.PENDING_SEND);
        when(provider.getMaxAttempts()).thenReturn(3);
        when(provider.sendForSigning(anyString(), any(), any(), any(), anyString()))
                .thenThrow(new RuntimeException("provider down"));

        service.sendToProvider(session);

        assertThat(session.getStatus()).isEqualTo(SessionStatus.PENDING_SEND);
        assertThat(session.getLastError()).isEqualTo("provider down");
        verify(outbox, never()).save(any());   // no completion event until retries exhaust
    }

    @Test
    void aSendThatExhaustsItsRetriesFailsAndEmitsCompletion() {
        SigningSession session = session(SessionStatus.PENDING_SEND);
        session.setAttempts(2);   // the next failure is the third
        when(provider.getMaxAttempts()).thenReturn(3);
        when(provider.sendForSigning(anyString(), any(), any(), any(), anyString()))
                .thenThrow(new RuntimeException("provider down"));

        service.sendToProvider(session);

        assertThat(session.getStatus()).isEqualTo(SessionStatus.FAILED);
        assertThat(completionResult()).isEqualTo("FAILED");
    }

    // --- callback ----------------------------------------------------------------------------

    @Test
    void aSignedCallbackOnASigningSessionCompletesItAndEmitsTheEvent() {
        SigningSession session = session(SessionStatus.SIGNING);
        when(sessions.findBySessionNo("SIG-1")).thenReturn(Optional.of(session));

        service.handleCallback("SIG-1", "MOCK-abc", "SIGNED", null);

        assertThat(session.getStatus()).isEqualTo(SessionStatus.SIGNED);
        verify(callbackLog).save(any());
        assertThat(completionResult()).isEqualTo("SIGNED");
    }

    @Test
    void aCallbackOnAnAlreadyTerminalSessionIsLoggedbutIgnored() {
        SigningSession session = session(SessionStatus.SIGNED);
        when(sessions.findBySessionNo("SIG-1")).thenReturn(Optional.of(session));

        service.handleCallback("SIG-1", "MOCK-abc", "FAILED", "too late");

        assertThat(session.getStatus()).isEqualTo(SessionStatus.SIGNED);
        verify(callbackLog).save(any());   // the raw webhook is still stored
        verify(outbox, never()).save(any());
    }

    @Test
    void aCallbackForAnUnknownSessionIsLoggedWithoutThrowing() {
        when(sessions.findBySessionNo("SIG-404")).thenReturn(Optional.empty());

        service.handleCallback("SIG-404", null, "SIGNED", null);

        verify(callbackLog).save(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void aCallbackThatOvertakesOurOwnSendCommitIsStillAppliedFromPendingSend() {
        // The guard accepts PENDING_SEND as well as SIGNING: a provider that calls back faster than
        // our send transaction commits must not be discarded (db-esign.md).
        SigningSession session = session(SessionStatus.PENDING_SEND);
        when(sessions.findBySessionNo("SIG-1")).thenReturn(Optional.of(session));

        service.handleCallback("SIG-1", "MOCK-abc", "SIGNED", null);

        assertThat(session.getStatus()).isEqualTo(SessionStatus.SIGNED);
        assertThat(completionResult()).isEqualTo("SIGNED");
    }

    @Test
    void aFailedCallbackCompletesTheSessionAsFailed() {
        SigningSession session = session(SessionStatus.SIGNING);
        when(sessions.findBySessionNo("SIG-1")).thenReturn(Optional.of(session));

        service.handleCallback("SIG-1", "MOCK-abc", "FAILED", "signer email bounced");

        assertThat(session.getStatus()).isEqualTo(SessionStatus.FAILED);
        assertThat(completionResult()).isEqualTo("FAILED");
    }

    @Test
    void theCompletionEventCarriesTheContractBillingAndNotificationConsume() {
        SigningSession session = session(SessionStatus.SIGNING);
        when(sessions.findBySessionNo("SIG-1")).thenReturn(Optional.of(session));

        service.handleCallback("SIG-1", "MOCK-abc", "SIGNED", null);

        var payload = completionPayload();
        assertThat(payload.get("session_id").asString()).isEqualTo(session.getId().toString());
        assertThat(payload.get("result").asString()).isEqualTo("SIGNED");
        assertThat(payload.get("document_no").asString()).isEqualTo("HD-1");
        assertThat(payload.get("requested_by").asString()).isEqualTo(session.getRequestedBy().toString());
        assertThat(payload.get("signer_name").asString()).isEqualTo("Signer");
    }

    // --- cancel ------------------------------------------------------------------------------

    @Test
    void cancellingAnActiveSessionCompletesItAndAudits() {
        SigningSession session = session(SessionStatus.SIGNING);
        UUID id = session.getId();
        when(sessions.findById(id)).thenReturn(Optional.of(session));

        service.cancelSession(id, UUID.randomUUID(), "Ops One", "changed mind");

        assertThat(session.getStatus()).isEqualTo(SessionStatus.CANCELLED);
        assertThat(completionResult()).isEqualTo("CANCELLED");
        verify(audit).record(anyString(), any(), anyString(), anyString(), anyString(), anyString(),
                any(), any());
    }

    @Test
    void cancellingATerminalSessionIsRejected() {
        SigningSession session = session(SessionStatus.SIGNED);
        UUID id = session.getId();
        when(sessions.findById(id)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.cancelSession(id, UUID.randomUUID(), "Ops One", "too late"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void cancellingAMissingSessionIsNotFound() {
        UUID id = UUID.randomUUID();
        when(sessions.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelSession(id, UUID.randomUUID(), "Ops One", "n/a"))
                .isInstanceOf(NotFoundException.class);
    }

    // --- helpers -----------------------------------------------------------------------------

    private SigningSession session(SessionStatus status) {
        SigningSession s = SigningSession.create("CONTRACT", UUID.randomUUID(), "HD-1", "ACME",
                "Signer", "s@acme.vn", UUID.randomUUID(), UUID.randomUUID(), "Req");
        s.setSessionNo("SIG-1");
        s.setStatus(status);
        return s;
    }

    /** The result field on the single esign.session_completed row the outbox received. */
    private String completionResult() {
        return completionPayload().get("result").asString();
    }

    /** The parsed payload of the single esign.session_completed row the outbox received. */
    private tools.jackson.databind.JsonNode completionPayload() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo("esign.session_completed");
        return new ObjectMapper().readTree(event.getPayload());
    }
}
