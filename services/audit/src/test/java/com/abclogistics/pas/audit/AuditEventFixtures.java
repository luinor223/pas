package com.abclogistics.pas.audit;

import com.abclogistics.pas.common.audit.AuditPayload;
import com.abclogistics.pas.common.outbox.EventRecords;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * {@code audit.recorded} records as the seven producers actually send them.
 *
 * <p>The value is the serialized {@link AuditPayload} — the same object {@code AuditRecorder}
 * writes to the outbox — with the {@code event_id} in a header, shaped by the real relay. The
 * first spec pass hand-wrote a full-envelope JSON string here, which nothing has ever published.
 */
final class AuditEventFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditEventFixtures() { }

    static ConsumerRecord<String, String> recorded(AuditPayload payload) {
        return EventRecords.consumed(EventRecords.auditRecorded(
                payload.entityType(), payload.entityId(), MAPPER.writeValueAsString(payload)));
    }

    /** A field edit: no status moved, so only the {@code changes} map says anything happened. */
    static AuditPayload fieldEdit(UUID entityId, String entityNo, Instant at) {
        return new AuditPayload("contract-service", "CONTRACT", entityId, entityNo, "UPDATE",
                UUID.randomUUID(), "Nguyen Thi Lan", "SALES", null, null,
                Map.of("paymentTerm", Map.of("from", "NET30", "to", "NET45")),
                null, "10.0.0.1", at);
    }

    static AuditPayload statusChange(UUID entityId, String action, String before, String after,
                                     UUID actorId, Instant at) {
        return new AuditPayload("contract-service", "CONTRACT", entityId, "HD-2026-0001", action,
                actorId, "Nguyen Thi Lan", "SALES", before, after, Map.of(),
                "Điều khoản thanh toán cần sửa", "10.0.0.1", at);
    }

    /** A scheduler action: no actor, so {@code actor_name} is the "system" snapshot. */
    static AuditPayload systemAction(UUID entityId, String action, Instant at) {
        return new AuditPayload("contract-service", "CONTRACT", entityId, "HD-2026-0001", action,
                null, "system", null, "APPROVED", "ACTIVE", Map.of(), null, null, at);
    }

    static AuditPayload by(String sourceService, String entityType, UUID entityId, String entityNo,
                           String action, UUID actorId, Instant at) {
        return new AuditPayload(sourceService, entityType, entityId, entityNo, action,
                actorId, "Nguyen Thi Lan", "ACCOUNTING", null, null, Map.of(), null, null, at);
    }
}
