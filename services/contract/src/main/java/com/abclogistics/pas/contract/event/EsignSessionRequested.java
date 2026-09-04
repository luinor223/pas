package com.abclogistics.pas.contract.event;

import java.util.UUID;

public record EsignSessionRequested(
        UUID idempotencyKey,
        String documentType,
        UUID documentId,
        String documentNo,
        String signerName,
        String signerEmail,
        String customerName,
        UUID requestedBy,
        String requestedByName
) {
    public static final String EVENT_TYPE = "esign.session_requested";
}
