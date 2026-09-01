package com.abclogistics.pas.audit.service;

import com.abclogistics.pas.audit.repository.AuditRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

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
        throw new UnsupportedOperationException("Phase B: insert on conflict do nothing");
    }
}
