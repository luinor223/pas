package com.abclogistics.pas.contract.event;

import java.util.UUID;

public record EsignSessionRequested(
        UUID idempotencyKey,
        String documentType,
        UUID documentId,
        String documentNo,
        String signerName,
        String signerEmail
) {
    public static final String EVENT_TYPE = "esign.session_requested";
}
