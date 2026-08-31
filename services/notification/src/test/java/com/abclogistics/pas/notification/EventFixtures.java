package com.abclogistics.pas.notification;

import com.abclogistics.pas.notification.event.EventEnvelope;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Envelopes shaped exactly as registry §4 defines them, so the tests spec the real wire form. */
final class EventFixtures {

    private EventFixtures() { }

    static EventEnvelope stepAssigned(UUID documentId, List<UUID> assignees) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instance_id", UUID.randomUUID().toString());
        payload.put("step_no", 1);
        payload.put("step_name", "Legal review");
        payload.put("assignee_ids", assignees.stream().map(UUID::toString).toList());
        payload.put("document_no", "HD-2026-0001");
        payload.put("customer_name", "ACME Co");
        return envelope("workflow.step_assigned", "CONTRACT", documentId, payload);
    }

    static EventEnvelope completed(UUID documentId, UUID requestedBy, String outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instance_id", UUID.randomUUID().toString());
        payload.put("outcome", outcome);
        payload.put("document_no", "HD-2026-0001");
        payload.put("requested_by", requestedBy.toString());
        return envelope("workflow.completed", "CONTRACT", documentId, payload);
    }

    static EventEnvelope periodLocked(String periodCode, String recipientRole) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("period_code", periodCode);
        payload.put("locked_by_name", "Nguyen Van A");
        payload.put("recipient_role", recipientRole);
        return envelope("operations.period_locked", "OPERATION_PERIOD", null, payload);
    }

    /**
     * D9 direct publish: no outbox row, and the id is derived from
     * {@code document.expiring:{documentId}:{expiresOn}} exactly as contract-service derives it,
     * so a re-warn carries the same id and an extension earns a new one.
     */
    static EventEnvelope documentExpiring(UUID documentId, String expiresOn, UUID ownerUserId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("document_no", "HD-2026-0001");
        payload.put("expires_on", expiresOn);
        payload.put("days_left", 30);
        payload.put("owner_user_id", ownerUserId.toString());
        UUID derived = UUID.nameUUIDFromBytes(
                "document.expiring:%s:%s".formatted(documentId, expiresOn)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new EventEnvelope(derived, "document.expiring", Instant.now(),
                null, "system", "CONTRACT", documentId, payload);
    }

    static EventEnvelope envelope(String eventType, String documentType, UUID documentId,
                                  Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID(), eventType, Instant.now(),
                UUID.randomUUID(), "Nguyen Van A", documentType, documentId, payload);
    }
}
