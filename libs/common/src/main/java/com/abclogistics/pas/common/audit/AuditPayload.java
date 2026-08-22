package com.abclogistics.pas.common.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** The {@code audit.recorded} event body consumed by audit-service. Actor fields are snapshots. */
public record AuditPayload(
        String entityType,
        UUID entityId,
        String action,
        UUID actorId,
        String actorName,
        String actorDepartment,
        Instant occurredAt,
        String beforeStatus,
        String afterStatus,
        String note,
        Map<String, Object> changes,
        String sourceService
) {
}
