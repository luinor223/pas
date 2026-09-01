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

    /**
     * {@code INSERT … ON CONFLICT DO NOTHING} keyed on the envelope {@code event_id}: the PK is
     * the dedup key, so no {@code processed_event} table exists here (db-audit.md). @param
     * eventId from the {@code event_id} header — the producer's outbox row id @param
     * payloadJson the record value: a serialized {@code AuditPayload}, never an envelope
     * @return true when this call inserted the row, false when it was already there @throws
     * com.abclogistics.pas.common.events.MalformedEventException when the value is not an
     * {@code AuditPayload} — permanent, so it reaches the DLT rather than retrying
     */
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
        // a payload missing these cannot be stored or found again; no retry fixes it
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
