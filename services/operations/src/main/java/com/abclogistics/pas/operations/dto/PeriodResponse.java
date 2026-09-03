package com.abclogistics.pas.operations.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PeriodResponse(
        UUID id,
        String periodCode,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        long volumeCount,
        UUID lockedBy,
        String lockedByName,
        Instant lockedAt,
        Instant createdAt,
        Instant updatedAt
) {}
