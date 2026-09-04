package com.abclogistics.pas.billing.listener;

import com.abclogistics.pas.billing.domain.PaymentStatement;
import com.abclogistics.pas.billing.domain.ProcessedEvent;
import com.abclogistics.pas.billing.domain.StatusHistory;
import com.abclogistics.pas.billing.repository.PaymentStatementRepository;
import com.abclogistics.pas.billing.repository.ProcessedEventRepository;
import com.abclogistics.pas.billing.repository.StatusHistoryRepository;
import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.security.SystemActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The single {@code pas.events} consumer for billing-service — one consumer group per service
 * (registry §4). A shared group across two {@code @KafkaListener} methods would split partitions
 * between them, so each event would reach only one container and be acked-and-ignored by the
 * wrong one. Dispatch here is on the {@code event_type} header, before deserializing anything.
 *
 * <p>Ordering inside each branch: dedup first (same-{@code event_id} redelivery is the normal
 * at-least-once case and skips quietly), then validation — a <em>new</em> event ID that names no
 * document, an unknown document, an unknown outcome, or a state that cannot legally move is a
 * producer bug or race and throws to the DLT loudly instead of acking silently.
 */
@Component
public class BillingEventListener {

    static final String WORKFLOW_COMPLETED = "workflow.completed";
    static final String ESIGN_SESSION_COMPLETED = "esign.session_completed";
    static final String DOCUMENT_TYPE = "PAYMENT_STATEMENT";

    private static final Logger log = LoggerFactory.getLogger(BillingEventListener.class);

    private final PaymentStatementRepository statements;
    private final ProcessedEventRepository processed;
    private final StatusHistoryRepository history;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;

    public BillingEventListener(PaymentStatementRepository statements,
                                ProcessedEventRepository processed,
                                StatusHistoryRepository history,
                                AuditRecorder audit,
                                ObjectMapper objectMapper) {
        this.statements = statements;
        this.processed = processed;
        this.history = history;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @KafkaListener(topics = "pas.events", groupId = "billing-service")
    public void onEvent(@Payload String payload,
                        @Header(name = "event_type", required = false) String eventType,
                        @Header(name = "document_type", required = false) String documentType,
                        @Header(name = "event_id", required = false) String eventId,
                        @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        if (!WORKFLOW_COMPLETED.equals(eventType) && !ESIGN_SESSION_COMPLETED.equals(eventType)) {
            return;
        }
        if (!DOCUMENT_TYPE.equals(documentType)) {
            return;
        }
        if (eventId == null) {
            throw new IllegalStateException("Record on pas.events has no event_id header, key=" + key);
        }
        UUID id;
        try {
            id = UUID.fromString(eventId);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Record on pas.events has a malformed event_id header: " + eventId);
        }
        if (processed.existsById(id)) {
            log.debug("Event {} already applied, skipping", id);
            return;
        }

        if (WORKFLOW_COMPLETED.equals(eventType)) {
            onWorkflowCompleted(payload, key, id);
        } else {
            onEsignSessionCompleted(payload, key, id);
        }
    }

    private void onWorkflowCompleted(String payload, String key, UUID id) {
        UUID documentId = requireDocumentId(key, payload, id, WORKFLOW_COMPLETED);
        PaymentStatement stmt = requireStatement(documentId, id, WORKFLOW_COMPLETED);
        String outcome = text(payload, "outcome");
        PaymentStatement.StatementStatus newStatus = switch (outcome == null ? "" : outcome) {
            case "APPROVED" -> PaymentStatement.StatementStatus.APPROVED;
            case "REJECTED" -> PaymentStatement.StatementStatus.REJECTED;
            case "REVISION_REQUESTED" -> PaymentStatement.StatementStatus.REVISION;
            default -> null;
        };
        if (newStatus == null) {
            throw new IllegalStateException(
                "workflow.completed carries an outcome this service has no status for: " + outcome);
        }
        if (stmt.getStatus() != PaymentStatement.StatementStatus.SUBMITTED) {
            throw new IllegalStateException(
                "workflow.completed %s targets statement %s in status %s (expected SUBMITTED)"
                    .formatted(id, stmt.getStatementNo(), stmt.getStatus()));
        }

        processed.save(ProcessedEvent.of(id));
        PaymentStatement.StatementStatus oldStatus = stmt.getStatus();
        stmt.setStatus(newStatus);
        statements.save(stmt);

        history.save(transitionOf(stmt, oldStatus, newStatus, StatusHistory.TriggerKind.W, text(payload, "instance_id")));
        audit.record("PAYMENT_STATEMENT", stmt.getId(), stmt.getStatementNo(), "WORKFLOW_COMPLETED",
            oldStatus.name(), newStatus.name(), "Approval " + outcome.toLowerCase(java.util.Locale.ROOT),
            Map.of("trigger", "W", "event_id", id.toString()));
        log.info("Statement {} status changed {} → {} by workflow.completed",
            stmt.getStatementNo(), oldStatus, newStatus);
    }

    private void onEsignSessionCompleted(String payload, String key, UUID id) {
        UUID documentId = requireDocumentId(key, payload, id, ESIGN_SESSION_COMPLETED);
        PaymentStatement stmt = requireStatement(documentId, id, ESIGN_SESSION_COMPLETED);
        String result = text(payload, "result");
        PaymentStatement.StatementStatus newStatus = switch (result == null ? "" : result) {
            case "SIGNED" -> PaymentStatement.StatementStatus.SIGNED;
            case "FAILED", "CANCELLED" -> PaymentStatement.StatementStatus.REVISION; // PAY-07
            default -> null;
        };
        if (newStatus == null) {
            throw new IllegalStateException(
                "esign.session_completed carries a result this service has no status for: " + result);
        }
        if (stmt.getStatus() != PaymentStatement.StatementStatus.SIGNING) {
            throw new IllegalStateException(
                "esign.session_completed %s targets statement %s in status %s (expected SIGNING)"
                    .formatted(id, stmt.getStatementNo(), stmt.getStatus()));
        }

        processed.save(ProcessedEvent.of(id));
        PaymentStatement.StatementStatus oldStatus = stmt.getStatus();
        stmt.setStatus(newStatus);
        statements.save(stmt);

        history.save(transitionOf(stmt, oldStatus, newStatus, StatusHistory.TriggerKind.E, text(payload, "session_id")));
        audit.record("PAYMENT_STATEMENT", stmt.getId(), stmt.getStatementNo(), "ESIGN_SESSION_COMPLETED",
            oldStatus.name(), newStatus.name(), "Signing " + result.toLowerCase(java.util.Locale.ROOT),
            Map.of("trigger", "E", "event_id", id.toString()));
        log.info("Statement {} status changed {} → {} by esign.session_completed",
            stmt.getStatementNo(), oldStatus, newStatus);
    }

    private UUID requireDocumentId(String key, String payload, UUID id, String eventType) {
        UUID documentId = documentId(key, payload);
        if (documentId == null) {
            throw new IllegalStateException(
                "%s carries no document id (key=%s, event=%s)".formatted(eventType, key, id));
        }
        return documentId;
    }

    private PaymentStatement requireStatement(UUID documentId, UUID id, String eventType) {
        return statements.findById(documentId).orElseThrow(() -> new IllegalStateException(
            "%s %s references unknown statement %s".formatted(eventType, id, documentId)));
    }

    private UUID documentId(String key, String payload) {
        try {
            if (key != null) return UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            // fall through to payload
        }
        String fromPayload = text(payload, "document_id");
        try {
            return fromPayload == null ? null : UUID.fromString(fromPayload);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private StatusHistory transitionOf(PaymentStatement stmt,
                                       PaymentStatement.StatementStatus from,
                                       PaymentStatement.StatementStatus to,
                                       StatusHistory.TriggerKind kind,
                                       String triggerRef) {
        StatusHistory h = new StatusHistory();
        h.setStatement(stmt);
        h.setFromStatus(from.name());
        h.setToStatus(to.name());
        h.setTriggerKind(kind);
        h.setTriggerRef(triggerRef);
        h.setActorId(SystemActor.ID);
        h.setActorName(SystemActor.NAME);
        h.setOccurredAt(Instant.now());
        return h;
    }

    private String text(String payload, String field) {
        JsonNode node = objectMapper.readTree(payload).get(field);
        return node == null || node.isNull() || node.asString().isEmpty() ? null : node.asString();
    }
}
