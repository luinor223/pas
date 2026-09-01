package com.abclogistics.pas.common.audit;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code audit.recorded} event body consumed by audit-service. Actor fields are snapshots.
 * Snake_case on the wire: registry §4 documents these field names, and every other event's
 * payload is built as a snake_case map, so this was the one event on the bus in camelCase.
 */
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
) {
    /** Compatibility constructor without entity_no / ip_address — for callers that don't have them yet. */
    public AuditPayload(
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
            String sourceService) {
        this(sourceService, entityType, entityId, null, action, actorId, actorName, actorDepartment,
                beforeStatus, afterStatus, changes == null ? Map.of() : changes, note, null, occurredAt);
    }
}
