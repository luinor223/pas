package com.abclogistics.pas.audit.dto;

import com.abclogistics.pas.audit.domain.AuditRecord;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditRecordResponse(UUID id, String sourceService, String entityType, UUID entityId,
                                  String entityNo, String action, UUID actorId, String actorName,
                                  String actorDepartment, String beforeStatus, String afterStatus,
                                  Map<String, Object> changes, String note, String ipAddress,
                                  Instant occurredAt) {

    public static AuditRecordResponse of(AuditRecord r) {
        return new AuditRecordResponse(r.getId(), r.getSourceService(), r.getEntityType(),
                r.getEntityId(), r.getEntityNo(), r.getAction(), r.getActorId(), r.getActorName(),
                r.getActorDepartment(), r.getBeforeStatus(), r.getAfterStatus(), r.getChanges(),
                r.getNote(), r.getIpAddress(), r.getOccurredAt());
    }
}
