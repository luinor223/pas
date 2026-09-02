package com.abclogistics.pas.audit.service;

import com.abclogistics.pas.audit.repository.AuditRecordRepository;
import com.abclogistics.pas.common.audit.AuditPayload;
import com.abclogistics.pas.common.events.MalformedEventException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/** The `pas.audit` sink. */
@Service
public class AuditIngestService {

    private final AuditRecordRepository records;
    private final ObjectMapper objectMapper;

    public AuditIngestService(AuditRecordRepository records, ObjectMapper objectMapper) {
        this.records = records;
        this.objectMapper = objectMapper;
    }

    /** Inserts once using {@code eventId} as both the primary key and dedup key. */
    @Transactional
    public boolean ingest(UUID eventId, String payloadJson) {
        AuditPayload payload = parse(payloadJson);
        return records.insertIgnoringDuplicate(
                eventId, payload.sourceService(), payload.entityType(), payload.entityId(),
                payload.entityNo(), payload.action(), payload.actorId(), payload.actorName(),
                payload.actorDepartment(), payload.beforeStatus(), payload.afterStatus(),
                writeChanges(payload.changes()), payload.note(), payload.ipAddress(),
                payload.occurredAt()) == 1;
    }

    private AuditPayload parse(String payloadJson) {
        AuditPayload payload;
        try {
            payload = objectMapper.readValue(payloadJson, AuditPayload.class);
        } catch (RuntimeException e) {
            throw new MalformedEventException("not an AuditPayload: " + e.getMessage());
        }
        // Required by the audit schema and search API.
        if (payload == null || payload.entityType() == null || payload.entityId() == null
                || payload.action() == null || payload.sourceService() == null
                || payload.occurredAt() == null) {
            throw new MalformedEventException("AuditPayload is missing a required field");
        }
        return payload;
    }

    private String writeChanges(Map<String, Object> changes) {
        return objectMapper.writeValueAsString(changes == null ? Map.of() : changes);
    }
}
