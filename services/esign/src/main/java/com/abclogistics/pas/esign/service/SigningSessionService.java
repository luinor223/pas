package com.abclogistics.pas.esign.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.esign.domain.SigningCallbackLog;
import com.abclogistics.pas.esign.domain.SigningSession;
import com.abclogistics.pas.esign.domain.StatusHistory;
import com.abclogistics.pas.esign.dto.SigningSessionResponse;
import com.abclogistics.pas.esign.repository.SigningCallbackLogRepository;
import com.abclogistics.pas.esign.repository.SigningSessionRepository;
import com.abclogistics.pas.esign.repository.StatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class SigningSessionService {

    private static final Logger log = LoggerFactory.getLogger(SigningSessionService.class);

    private final SigningSessionRepository sessionRepo;
    private final SigningCallbackLogRepository callbackLogRepo;
    private final StatusHistoryRepository statusHistoryRepo;
    private final OutboxRepository outboxRepo;
    private final AuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;

    public SigningSessionService(SigningSessionRepository sessionRepo,
                                  SigningCallbackLogRepository callbackLogRepo,
                                  StatusHistoryRepository statusHistoryRepo,
                                  OutboxRepository outboxRepo,
                                  AuditRecorder auditRecorder,
                                  ObjectMapper objectMapper) {
        this.sessionRepo = sessionRepo;
        this.callbackLogRepo = callbackLogRepo;
        this.statusHistoryRepo = statusHistoryRepo;
        this.outboxRepo = outboxRepo;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
    }

    /** The relay dispatches this outbox row to the provider (HTTP), so the send commits with the
     *  session row and inherits the shared retry/park machinery. */
    public static final String PROVIDER_SEND = "esign.provider_send";

    @Transactional
    public SigningSession createSession(String documentTypeCode, UUID documentId,
                                         String documentNo, String customerName,
                                         String signerName, String signerEmail,
                                         UUID idempotencyKey, UUID requestedBy, String requestedByName) {
        // Idempotency check on permanent key
        Optional<SigningSession> existing = sessionRepo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("Session already exists for idempotency_key={}, returning existing session {}",
                idempotencyKey, existing.get().getSessionNo());
            return existing.get();
        }

        // Partial unique check: one active session per document
        List<SigningSession.SessionStatus> activeStatuses = List.of(
            SigningSession.SessionStatus.PENDING_SEND,
            SigningSession.SessionStatus.SIGNING
        );
        if (sessionRepo.existsByDocumentTypeCodeAndDocumentIdAndStatusIn(documentTypeCode, documentId, activeStatuses)) {
            throw new FailedPreconditionException(
                "An active signing session already exists for " + documentTypeCode + " " + documentId);
        }

        // Fetch DB-generated session_no BEFORE creating entity to avoid auto-flush with null session_no
        long seq = sessionRepo.nextSessionNoSeq();
        String sessionNo = "SIG-" + seq;

        SigningSession session = SigningSession.create(
            documentTypeCode, documentId, documentNo, customerName,
            signerName, signerEmail, idempotencyKey, requestedBy, requestedByName
        );
        session.setSessionNo(sessionNo);

        try {
            session = sessionRepo.saveAndFlush(session);
        } catch (DataIntegrityViolationException e) {
            // lost a race past the pre-checks: same key (return the winner) or the active-session
            // index (a genuine concurrent double-send, refused for good)
            return sessionRepo.findByIdempotencyKey(idempotencyKey).orElseThrow(() ->
                new FailedPreconditionException(
                    "An active signing session already exists for " + documentTypeCode + " " + documentId));
        }

        // Status history (D17)
        StatusHistory history = StatusHistory.create(
            session, null, "PENDING_SEND",
            StatusHistory.TriggerKind.U, null,
            requestedBy, requestedByName, "Session created"
        );
        session.addStatusHistory(history);
        sessionRepo.save(session);

        // D16: the send to the external provider is dispatched from the outbox (atomic with the row,
        // retried and parked by the shared relay), never inline on the create call.
        outboxRepo.save(OutboxEvent.event(PROVIDER_SEND, documentTypeCode, session.getId(),
            "{\"session_id\":\"%s\",\"session_no\":\"%s\"}".formatted(session.getId(), session.getSessionNo())));

        log.info("Created signing session {} for {} {}", session.getSessionNo(), documentTypeCode, documentId);
        return session;
    }

    /** Relay success: the provider accepted the send. PENDING_SEND to SIGNING, idempotent (a re-run
     *  after a lost ack finds the session already SIGNING and no-ops). Runs in its own transaction. */
    @Transactional
    public void markSent(UUID sessionId, String providerRef, int attempts) {
        SigningSession session = sessionRepo.findById(sessionId).orElseThrow();
        if (session.getStatus() != SigningSession.SessionStatus.PENDING_SEND) {
            return;
        }
        session.setProviderRef(providerRef);
        session.setAttempts(attempts);
        session.setStatus(SigningSession.SessionStatus.SIGNING);
        session.setSentAt(Instant.now());
        StatusHistory history = StatusHistory.create(
            session, "PENDING_SEND", "SIGNING",
            StatusHistory.TriggerKind.S, null,
            null, "System", "Sent to provider"
        );
        session.addStatusHistory(history);
        sessionRepo.save(session);
    }

    /** Relay park: the send exhausted its retries or was permanently refused (APR-07). PENDING_SEND
     *  to FAILED plus the completion event. Runs inside the relay's parking transaction. */
    @Transactional
    public void failSend(UUID sessionId, int attempts, String error) {
        SigningSession session = sessionRepo.findById(sessionId).orElseThrow();
        if (session.getStatus() != SigningSession.SessionStatus.PENDING_SEND) {
            return;
        }
        session.setAttempts(attempts);
        session.setLastError(error);
        session.setStatus(SigningSession.SessionStatus.FAILED);
        session.setCompletedAt(Instant.now());
        StatusHistory history = StatusHistory.create(
            session, "PENDING_SEND", "FAILED",
            StatusHistory.TriggerKind.S, null,
            null, "System", error
        );
        session.addStatusHistory(history);
        sessionRepo.save(session);
        emitSessionCompleted(session, SigningSession.SessionStatus.FAILED, error);
    }

    /**
     * Handle callback from the esign provider (APR-06).
     * Callback guard accepts PENDING_SEND and SIGNING (not just SIGNING) — a provider that
     * calls back faster than our 202-handling transaction commits would otherwise be discarded.
     */
    @Transactional
    public void handleCallback(String sessionNo, String providerRef, String result, String error) {
        // Find session by session_no (primary) or provider_ref (fallback)
        SigningSession session = sessionRepo.findBySessionNo(sessionNo).orElse(null);

        if (session == null && providerRef != null && !providerRef.isBlank()) {
            // Fallback: try to find by provider_ref
            Optional<SigningCallbackLog> existingLog = callbackLogRepo.findByProviderRef(providerRef);
            if (existingLog.isPresent() && existingLog.get().getSession() != null) {
                session = sessionRepo.findById(existingLog.get().getSession().getId()).orElse(null);
            }
        }

        // Log the callback regardless (APR-06: every raw webhook stored)
        SigningCallbackLog callbackLog = SigningCallbackLog.create(session, providerRef, result, null);
        callbackLogRepo.save(callbackLog);

        if (session == null) {
            log.warn("Callback for unknown session_no={}, provider_ref={}", sessionNo, providerRef);
            return;
        }

        // Version guard: optimistic lock on status (APR-06)
        // Accept PENDING_SEND and SIGNING — provider may callback faster than our tx commits
        String fromStatus = session.getStatus().name();
        if (session.getStatus() != SigningSession.SessionStatus.PENDING_SEND
            && session.getStatus() != SigningSession.SessionStatus.SIGNING) {
            log.debug("Ignoring callback for session {} in terminal status {}", session.getSessionNo(), session.getStatus());
            return;
        }

        SigningSession.SessionStatus newStatus = switch (result) {
            case "SIGNED" -> SigningSession.SessionStatus.SIGNED;
            case "FAILED", "CANCELLED" -> SigningSession.SessionStatus.FAILED;
            default -> {
                log.warn("Unknown callback result: {} for session {}", result, session.getSessionNo());
                yield null;
            }
        };

        if (newStatus == null) return;

        session.setStatus(newStatus);
        session.setCompletedAt(Instant.now());

        StatusHistory history = StatusHistory.create(
            session, fromStatus, newStatus.name(),
            StatusHistory.TriggerKind.E, null,
            session.getRequestedBy(), session.getRequestedByName(),
            error != null ? error : "Provider callback: " + result
        );
        session.addStatusHistory(history);
        sessionRepo.save(session);

        // Emit esign.session_completed event via outbox
        emitSessionCompleted(session, newStatus, error);

        log.info("Session {} callback applied: {} -> {}", session.getSessionNo(), fromStatus, newStatus);
    }

    /**
     * Cancel a signing session (user action only, CANCELLED is never provider-reported).
     * Gated by esign:cancel permission, valid from PENDING_SEND or SIGNING.
     */
    @Transactional
    public void cancelSession(UUID sessionId, UUID actorId, String actorName, String reason) {
        SigningSession session = sessionRepo.findById(sessionId)
            .orElseThrow(() -> new NotFoundException("Signing session not found: " + sessionId));

        if (!session.canCancel()) {
            throw new ConflictException("Cannot cancel session in status " + session.getStatus());
        }

        String fromStatus = session.getStatus().name();
        session.setStatus(SigningSession.SessionStatus.CANCELLED);
        session.setCompletedAt(Instant.now());

        StatusHistory history = StatusHistory.create(
            session, fromStatus, "CANCELLED",
            StatusHistory.TriggerKind.U, null,
            actorId, actorName, reason
        );
        session.addStatusHistory(history);
        sessionRepo.save(session);

        // Emit esign.session_completed event via outbox
        emitSessionCompleted(session, SigningSession.SessionStatus.CANCELLED, null);

        // Audit (D15: centralized audit via outbox)
        auditRecorder.record("signing_session", session.getId(), session.getSessionNo(),
            "esign:cancel_signing", fromStatus, "CANCELLED", reason, Map.of());

        log.info("Session {} cancelled by {}", session.getSessionNo(), actorName);
    }

    @Transactional(readOnly = true)
    public Page<SigningSessionResponse> listSessions(String status, Pageable pageable) {
        Page<SigningSession> page;
        if (status != null && !status.isBlank()) {
            SigningSession.SessionStatus s = SigningSession.SessionStatus.valueOf(status.toUpperCase());
            page = sessionRepo.findByStatus(s, pageable);
        } else {
            page = sessionRepo.findAll(pageable);
        }
        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public SigningSessionResponse getSession(UUID id) {
        SigningSession session = sessionRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("Signing session not found: " + id));
        return toResponse(session);
    }

    @Transactional(readOnly = true)
    public SigningSessionResponse getSessionBySessionNo(String sessionNo) {
        SigningSession session = sessionRepo.findBySessionNo(sessionNo)
            .orElseThrow(() -> new NotFoundException("Signing session not found: " + sessionNo));
        return toResponse(session);
    }

    @Transactional(readOnly = true)
    public List<SigningSessionResponse> getSessionsByDocument(String documentType, UUID documentId) {
        return sessionRepo.findAllByDocument(documentType, documentId).stream()
            .map(this::toResponse)
            .toList();
    }

    private void emitSessionCompleted(SigningSession session, SigningSession.SessionStatus result, String error) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                "session_id", session.getId().toString(),
                "result", result.name(),
                "error", error != null ? error : "",
                "document_type", session.getDocumentTypeCode(),
                "document_id", session.getDocumentId().toString(),
                "document_no", session.getDocumentNo() != null ? session.getDocumentNo() : "",
                "requested_by", session.getRequestedBy() != null ? session.getRequestedBy().toString() : "",
                "signer_name", session.getSignerName() != null ? session.getSignerName() : ""
            ));

            OutboxEvent event = OutboxEvent.event(
                "esign.session_completed",
                session.getDocumentTypeCode(),
                session.getDocumentId(),
                payload
            );
            outboxRepo.save(event);
        } catch (Exception e) {
            log.error("Failed to emit session_completed event: {}", e.getMessage(), e);
        }
    }

    private SigningSessionResponse toResponse(SigningSession s) {
        return new SigningSessionResponse(
            s.getId(), s.getSessionNo(), s.getDocumentTypeCode(), s.getDocumentId(),
            s.getDocumentNo(), s.getCustomerName(), s.getSignerName(), s.getSignerEmail(),
            s.getProvider(), s.getProviderRef(), s.getStatus().name(), s.getAttempts(),
            s.getLastError(), s.getRequestedByName(), s.getSentAt(), s.getCompletedAt(), s.getCreatedAt()
        );
    }
}
