package com.abclogistics.pas.notification;

import com.abclogistics.pas.common.outbox.EventRecords;
import com.abclogistics.pas.notification.event.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records as the producers actually send them, not as this service wishes they arrived.
 *
 * <p>Every fixture goes through {@link EventRecords}, which shapes relayed events with the real
 * {@code OutboxRelay#kafkaRecord}: value = {@code payload{}} alone, envelope fields in headers,
 * key = the document id. The first version of this file hand-built full-envelope values, which no
 * producer has ever sent — the tests all passed and the consumer would have failed on its first
 * real message. Payload keys below are copied from registry §4's "Payload extras" column.
 */
final class EventFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private EventFixtures() { }

    // ---- relayed events (outbox → OutboxRelay) -----------------------------------------------

    static ConsumerRecord<String, String> stepAssigned(UUID documentId, List<UUID> assignees) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instance_id", UUID.randomUUID().toString());
        payload.put("step_no", 1);
        payload.put("step_name", "Legal review");
        payload.put("assignee_ids", assignees.stream().map(UUID::toString).toList());
        payload.put("document_no", "HD-2026-0001");
        payload.put("customer_name", "ACME Co");
        return outboxed("workflow.step_assigned", "CONTRACT", documentId, payload);
    }

    static ConsumerRecord<String, String> stepActioned(UUID documentId, UUID requestedBy, String action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instance_id", UUID.randomUUID().toString());
        payload.put("step_no", 2);
        payload.put("action", action);
        payload.put("comment", "Điều khoản thanh toán cần sửa");
        payload.put("document_no", "HD-2026-0001");
        payload.put("requested_by", requestedBy.toString());
        payload.put("requested_by_name", "Nguyen Van A");
        return outboxed("workflow.step_actioned", "CONTRACT", documentId, payload);
    }

    static ConsumerRecord<String, String> completed(UUID documentId, UUID requestedBy, String outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instance_id", UUID.randomUUID().toString());
        payload.put("outcome", outcome);
        payload.put("document_no", "HD-2026-0001");
        payload.put("requested_by", requestedBy.toString());
        payload.put("requested_by_name", "Nguyen Van A");
        return outboxed("workflow.completed", "CONTRACT", documentId, payload);
    }

    static ConsumerRecord<String, String> esignCompleted(UUID documentId, UUID requestedBy, String result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", UUID.randomUUID().toString());
        payload.put("result", result);
        payload.put("document_no", "HD-2026-0001");
        payload.put("requested_by", requestedBy.toString());
        payload.put("signer_name", "Tran Thi B");
        return outboxed("esign.session_completed", "CONTRACT", documentId, payload);
    }

    /** An event this service does not consume — it shares the topic and must be skipped. */
    static ConsumerRecord<String, String> instanceStarted(UUID documentId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instance_id", UUID.randomUUID().toString());
        payload.put("document_no", "HD-2026-0001");
        payload.put("priority", "NORMAL");
        return outboxed("workflow.instance_started", "CONTRACT", documentId, payload);
    }

    // ---- direct publishes (D9: no outbox row, derived event_id) -------------------------------

    /**
     * {@code ContractStatusScheduler#eventId} restated: the id is derived from the document and the
     * term being warned for, so a re-warn carries the same id and an extension earns a new one.
     */
    static ConsumerRecord<String, String> documentExpiring(UUID documentId, String expiresOn, UUID ownerUserId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("document_no", "HD-2026-0001");
        payload.put("expires_on", expiresOn);
        payload.put("days_left", 30);
        payload.put("owner_user_id", ownerUserId.toString());
        UUID eventId = derived("document.expiring:%s:%s".formatted(documentId, expiresOn));
        return EventRecords.consumed(EventRecords.directPublish(
                eventId, "document.expiring", "CONTRACT", documentId, json(payload)));
    }

    /** {@code SlaScheduler#eventId} restated: step instance + the deadline it is overdue against. */
    static ConsumerRecord<String, String> stepOverdue(UUID documentId, UUID stepInstanceId,
                                                      String deadline, List<UUID> assignees) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instance_id", UUID.randomUUID().toString());
        payload.put("step_no", 1);
        payload.put("step_name", "Legal review");
        payload.put("assignee_ids", assignees.stream().map(UUID::toString).toList());
        payload.put("waiting_hours", 30);
        payload.put("sla_hours", 24);
        payload.put("document_no", "HD-2026-0001");
        UUID eventId = derived("workflow.step_overdue:%s:%s".formatted(stepInstanceId, deadline));
        return EventRecords.consumed(EventRecords.directPublish(
                eventId, "workflow.step_overdue", "CONTRACT", documentId, json(payload)));
    }

    /** Role-addressed and not about a document, so it keys on the period code (registry §4). */
    static ConsumerRecord<String, String> periodLocked(String periodCode, String recipientRole) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("period_code", periodCode);
        payload.put("locked_by_name", "Nguyen Van A");
        payload.put("recipient_role", recipientRole);
        UUID eventId = derived("operations.period_locked:%s".formatted(periodCode));
        // keyed on the period code, not a document id — this event is not about a document
        return EventRecords.consumed(EventRecords.directPublish(
                eventId, "operations.period_locked", "OPERATION_PERIOD", periodCode, json(payload)));
    }

    // ---- helpers ------------------------------------------------------------------------------

    static EventEnvelope envelope(ConsumerRecord<String, String> record) {
        return EventEnvelope.from(record, MAPPER);
    }

    private static ConsumerRecord<String, String> outboxed(String eventType, String documentType,
                                                           UUID documentId, Map<String, Object> payload) {
        return EventRecords.consumed(
                EventRecords.outboxed(eventType, documentType, documentId, json(payload)));
    }

    private static String json(Map<String, Object> payload) {
        return MAPPER.writeValueAsString(payload);
    }

    private static UUID derived(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
