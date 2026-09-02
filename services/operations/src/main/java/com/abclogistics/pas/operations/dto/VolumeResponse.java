package com.abclogistics.pas.operations.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record VolumeResponse(
        UUID id,
        String recordNo,
        String periodCode,
        UUID contractId,
        String customerName,
        String serviceCode,
        String serviceName,
        String unit,
        BigDecimal quantity,
        String note,
        Instant createdAt,
        UUID createdBy,
        Instant updatedAt
) {}
