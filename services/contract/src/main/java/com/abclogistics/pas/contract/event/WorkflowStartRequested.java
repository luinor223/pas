package com.abclogistics.pas.contract.event;

import java.util.UUID;

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
