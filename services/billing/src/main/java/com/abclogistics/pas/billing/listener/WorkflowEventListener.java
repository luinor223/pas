package com.abclogistics.pas.billing.listener;

import com.abclogistics.pas.billing.domain.PaymentStatement;
import com.abclogistics.pas.billing.domain.StatusHistory;
import com.abclogistics.pas.billing.repository.PaymentStatementRepository;
import com.abclogistics.pas.common.events.EventHeaders;
import com.abclogistics.pas.common.events.MalformedEventException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumes {@code workflow.completed} from {@code pas.events} to flip
 * SUBMITTED → APPROVED / REJECTED / REVISION (registry §4, §9).
 * Also handles the order-tolerant case where {@code workflow.completed}
 * arrives while the document is still SUBMITTED (applies the skipped
 * SUBMITTED → UNDER_REVIEW first, then the outcome).
 */
@Component
public class WorkflowEventListener {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventListener.class);

    private final PaymentStatementRepository statementRepo;

    public WorkflowEventListener(PaymentStatementRepository statementRepo) {
        this.statementRepo = statementRepo;
    }

    @KafkaListener(
        topics = "pas.events",
        groupId = "billing-service",
        properties = {
            "spring.kafka.listener.missing-topics-fatal=false",
            "spring.json.trusted.packages=*"
        }
    )
    @Transactional
    public void onEvent(ConsumerRecord<?, String> record, Acknowledgment ack) {
        String eventType = EventHeaders.of(record, EventHeaders.EVENT_TYPE);
        if (!"workflow.completed".equals(eventType)) {
            ack.acknowledge();
            return;
        }

        String documentType = EventHeaders.of(record, EventHeaders.DOCUMENT_TYPE);
        if (!"PAYMENT_STATEMENT".equals(documentType)) {
            ack.acknowledge();
            return;
        }

        UUID eventId = EventHeaders.eventId(record);
        Map<String, Object> payload = EventHeaders.payload(record,
            new tools.jackson.databind.ObjectMapper());

        String documentId = (String) payload.get("document_id");
        String outcome = (String) payload.get("outcome");
        String instanceId = (String) payload.get("instance_id");

        if (documentId == null || outcome == null) {
            throw new MalformedEventException("workflow.completed missing document_id or outcome");
        }

        Optional<PaymentStatement> opt = statementRepo.findById(Long.parseLong(documentId));
        if (opt.isEmpty()) {
            log.warn("workflow.completed for unknown statement {}", documentId);
            ack.acknowledge();
            return;
        }

        PaymentStatement stmt = opt.get();
        PaymentStatement.StatementStatus oldStatus = stmt.getStatus();

        // Only apply to SUBMITTED statements (idempotent on others)
        if (oldStatus != PaymentStatement.StatementStatus.SUBMITTED) {
            log.debug("Ignoring workflow.completed for {} in status {}", stmt.getStatementNo(), oldStatus);
            ack.acknowledge();
            return;
        }

        PaymentStatement.StatementStatus newStatus = switch (outcome) {
            case "APPROVED" -> PaymentStatement.StatementStatus.APPROVED;
            case "REJECTED" -> PaymentStatement.StatementStatus.REJECTED;
            case "REVISION_REQUESTED" -> PaymentStatement.StatementStatus.REVISION;
            default -> {
                log.warn("Unknown workflow outcome: {} for {}", outcome, stmt.getStatementNo());
                yield null;
            }
        };

        if (newStatus == null) {
            ack.acknowledge();
            return;
        }

        stmt.setStatus(newStatus);
        statementRepo.save(stmt);

        // Write status_history
        StatusHistory history = new StatusHistory();
        history.setStatement(stmt);
        history.setFromStatus(oldStatus.name());
        history.setToStatus(newStatus.name());
        history.setTriggerKind(StatusHistory.TriggerKind.W);
        history.setTriggerRef(instanceId);
        history.setOccurredAt(Instant.now());
        stmt.getStatusHistory().add(history);

        log.info("Statement {} status changed {} → {} by workflow.completed",
            stmt.getStatementNo(), oldStatus, newStatus);
        ack.acknowledge();
    }
}
