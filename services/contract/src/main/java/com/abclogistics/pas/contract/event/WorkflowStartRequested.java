package com.abclogistics.pas.contract.event;

import java.util.UUID;

/**
 * The {@code workflow.start_requested} outbox payload (D4). Everything
 * {@code WorkflowInternal.StartInstance} needs is captured at submit time, so the relay can retry
 * without re-reading the document — and without the retry seeing a document that has since moved.
 *
 * <p>{@code idempotencyKey} is generated ONCE, here. Every retry sends the same key, so a lost ack
 * resolves to the instance that already exists rather than starting a second one.
 */
public record WorkflowStartRequested(
        UUID idempotencyKey,
        String documentType,
        UUID documentId,
        String documentNo,
        String customerName,
        String priority,
        UUID requestedById,
        String requestedByName
) {
    public static final String EVENT_TYPE = "workflow.start_requested";
}
