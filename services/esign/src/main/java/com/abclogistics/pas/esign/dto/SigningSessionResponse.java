package com.abclogistics.pas.esign.dto;

import java.time.Instant;
import java.util.UUID;

public record SigningSessionResponse(
    UUID id,
    String sessionNo,
    String documentTypeCode,
    UUID documentId,
    String documentNo,
    String customerName,
    String signerName,
    String signerEmail,
    String provider,
    String providerRef,
    String status,
    int attempts,
    String lastError,
    String requestedByName,
    Instant sentAt,
    Instant completedAt,
    Instant createdAt
) {}
