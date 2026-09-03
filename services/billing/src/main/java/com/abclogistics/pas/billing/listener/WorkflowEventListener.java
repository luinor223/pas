package com.abclogistics.pas.billing.listener;

import com.abclogistics.pas.billing.domain.PaymentStatement;
import com.abclogistics.pas.billing.domain.ProcessedEvent;
import com.abclogistics.pas.billing.domain.StatusHistory;
import com.abclogistics.pas.billing.repository.PaymentStatementRepository;
import com.abclogistics.pas.billing.repository.ProcessedEventRepository;
import com.abclogistics.pas.billing.repository.StatusHistoryRepository;
import com.abclogistics.pas.common.audit.AuditRecorder;
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
 * Consumes {@code workflow.completed} to flip SUBMITTED → APPROVED / REJECTED / REVISION
 * (registry §4, §9). Idempotent via {@code processed_event}; status + history + audit commit
 * together (D15/D17).
 */
@Component
public class WorkflowEventListener {

    static final String COMPLETED = "workflow.completed";

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventListener.class);

    private final PaymentStatementRepository statements;
    private final ProcessedEventRepository processed;
    private final StatusHistoryRepository history;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;

    public WorkflowEventListener(PaymentStatementRepository statements,
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
        if (!COMPLETED.equals(eventType)) {
            return;
        }
        if (!"PAYMENT_STATEMENT".equals(documentType)) {
            return;
        }
        if (eventId == null) {
            throw new IllegalStateException("Record on pas.events has no event_id header, key=" + key);
        }
        UUID id = UUID.fromString(eventId);
        if (processed.existsById(id)) {
            log.debug("Event {} already applied, skipping", id);
            return;
        }
        processed.save(ProcessedEvent.of(id));

        UUID documentId = documentId(key, payload);
        if (documentId == null) {
            log.warn("workflow.completed carries no document id (key={}, event={})", key, id);
            return;
        }
        PaymentStatement stmt = statements.findById(documentId).orElse(null);
        if (stmt == null) {
            log.warn("workflow.completed for unknown statement {}", documentId);
            return;
        }
        if (stmt.getStatus() != PaymentStatement.StatementStatus.SUBMITTED) {
            log.debug("Ignoring workflow.completed for {} in status {}", stmt.getStatementNo(), stmt.getStatus());
            return;
        }

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
        h.setOccurredAt(Instant.now());
        return h;
    }

    private String text(String payload, String field) {
        JsonNode node = objectMapper.readTree(payload).get(field);
        return node == null || node.isNull() || node.asString().isEmpty() ? null : node.asString();
    }
}
