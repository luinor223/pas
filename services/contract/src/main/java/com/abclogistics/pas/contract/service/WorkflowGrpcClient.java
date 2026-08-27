package com.abclogistics.pas.contract.service;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Thin wrapper over {@code WorkflowInternal} (D16, gRPC on :5051).
 *
 * <p>{@code NOT_FOUND} from {@link #getInstanceByDocument} is not an error: it is D4's dispatch
 * window, rendered as {@code INITIALIZATION_PENDING} from local status and never retried here.
 */
@Component
public class WorkflowGrpcClient {

    /** Read-only pre-submit check. FAILED_PRECONDITION surfaces to the caller as 412. */
    public void validateStartable(String documentTypeCode) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /** Called only by the outbox relay, never inline with the submit transaction (D4). */
    public UUID startInstance(UUID idempotencyKey, String documentTypeCode, UUID documentId,
                              String documentNo, String customerName, String priority,
                              UUID requestedById, String requestedByName) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /** Retried on NOT_FOUND — the instance may not exist yet (M2). */
    public void cancelInstance(UUID documentId, String documentTypeCode, String reason) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    public Object getInstanceByDocument(String documentTypeCode, UUID documentId) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }
}
