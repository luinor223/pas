package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.contract.domain.ApprovableDocument;
import com.abclogistics.pas.contract.domain.Customer;
import com.abclogistics.pas.contract.domain.CustomerContact;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.SigningRequestGuard;
import com.abclogistics.pas.contract.error.UnprocessableEntityException;
import com.abclogistics.pas.contract.event.EsignSessionRequested;
import com.abclogistics.pas.contract.repository.SigningRequestGuardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Queues the identical, idempotent e-sign intent for either contract document type. */
@Service
public class SigningRequestService {

    public record State(boolean canSendForSigning, boolean requestQueued, UUID sessionId) { }

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final CustomerService customers;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final AuditRecorder audit;
    private final SigningRequestGuardRepository guards;

    public SigningRequestService(CustomerService customers, OutboxRepository outbox,
                                 ObjectMapper objectMapper, AuditRecorder audit,
                                 SigningRequestGuardRepository guards) {
        this.customers = customers;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.guards = guards;
    }

    /** Caller owns the document-row lock, so creating or reusing the guard is race-free. */
    public boolean queue(ApprovableDocument document, Customer customer) {
        if (document.getStatus() != DocumentStatus.APPROVED) {
            throw new ConflictException(
                    "DOCUMENT_NOT_APPROVED",
                    "This document must be approved before it can be sent for signature.",
                    "This document must be approved before it can be sent for signature.");
        }

        String documentType = document.entityType().name();
        SigningRequestGuard guard = guards.findForUpdate(documentType, document.getId()).orElse(null);
        if (guard != null && guard.isActive()) {
            return false;
        }

        CustomerContact signer = signerFor(customer);
        UUID idempotencyKey = UUID.randomUUID();
        if (guard == null) {
            guard = SigningRequestGuard.active(documentType, document.getId(), idempotencyKey);
        } else {
            guard.activate(idempotencyKey);
        }
        guards.save(guard);
        EsignSessionRequested payload = new EsignSessionRequested(
                idempotencyKey, documentType, document.getId(),
                document.getDocumentNo(), signer.getFullName(), signer.getEmail(),
                customer.getName(), SecurityUtils.currentUserIdOrSystem(),
                SecurityUtils.currentUserNameOrSystem());
        outbox.save(OutboxEvent.event(EsignSessionRequested.EVENT_TYPE,
                document.entityType().name(), document.getId(), objectMapper.writeValueAsString(payload)));
        audit.record(document.entityType().name(), document.getId(), document.getDocumentNo(),
                "SEND_FOR_SIGNING", null, null,
                "Sent for e-signature to %s".formatted(signer.getEmail()),
                Map.of("signerName", signer.getFullName(), "signerEmail", signer.getEmail()));
        return true;
    }

    public State state(ApprovableDocument document) {
        SigningRequestGuard guard = guards.findById(
                new SigningRequestGuard.Key(document.entityType().name(), document.getId()))
                .filter(SigningRequestGuard::isActive)
                .orElse(null);
        return new State(
                SecurityUtils.hasPermission("esign:send")
                        && document.getStatus() == DocumentStatus.APPROVED && guard == null,
                guard != null && guard.getSessionId() == null,
                guard == null ? null : guard.getSessionId());
    }

    /** Records the callee's stable session identity before the owner outbox row is published. */
    @Transactional
    public void associateSession(String documentType, UUID documentId,
                                 UUID idempotencyKey, UUID sessionId) {
        guards.findForUpdate(documentType, documentId).ifPresent(guard -> {
            if (guard.isActive() && guard.getIdempotencyKey().equals(idempotencyKey)) {
                guard.associateSession(sessionId);
            }
        });
    }

    /** Releases only the matching generation, so a delayed old completion cannot release a retry. */
    @Transactional
    public void release(String documentType, UUID documentId,
                        UUID idempotencyKey, UUID sessionId) {
        guards.findForUpdate(documentType, documentId).ifPresent(guard -> {
            if (!guard.isActive()) {
                return;
            }
            if (idempotencyKey != null && guard.getIdempotencyKey().equals(idempotencyKey)) {
                guard.release();
                return;
            }
            if (idempotencyKey == null && guard.getSessionId() == null) {
                throw new IllegalStateException(
                        "Signing session has not been associated with its request guard yet");
            }
            if (idempotencyKey == null && guard.getSessionId().equals(sessionId)) {
                guard.release();
            }
        });
    }

    private CustomerContact signerFor(Customer customer) {
        CustomerContact signer = customers.primaryContactOf(customer.getId())
                .orElseThrow(() -> new UnprocessableEntityException(
                        "Customer %s has no primary contact; there is nobody to address the signature request to"
                                .formatted(customer.getCode())));
        if (RequestValues.blankToNull(signer.getFullName()) == null) {
            throw new UnprocessableEntityException("The customer's primary contact has no signer name");
        }
        String email = RequestValues.blankToNull(signer.getEmail());
        if (email == null) {
            throw new UnprocessableEntityException(
                    "Primary contact %s has no email address; the signature request has nowhere to go"
                            .formatted(signer.getFullName()));
        }
        if (!EMAIL.matcher(email).matches()) {
            throw new UnprocessableEntityException(
                    "Primary contact %s does not have a valid email address"
                            .formatted(signer.getFullName()));
        }
        return signer;
    }
}
