package com.abclogistics.pas.esign.dto;

import java.util.UUID;

public record CreateSigningSessionRequest(
    String documentType,
    UUID documentId,
    String documentNo,
    String customerName,
    String signerName,
    String signerEmail
) {}
