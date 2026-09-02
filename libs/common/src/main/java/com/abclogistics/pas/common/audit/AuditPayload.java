package com.abclogistics.pas.common.audit;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Snake-case payload for {@code audit.recorded}; actor fields are snapshots. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AuditPayload(
        String sourceService,
        String entityType,
        UUID entityId,
        String entityNo,
        String action,
        UUID actorId,
        String actorName,
        String actorDepartment,
        String beforeStatus,
        String afterStatus,
        Map<String, Object> changes,
        String note,
        String ipAddress,
        Instant occurredAt
) { }
