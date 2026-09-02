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
 * Consumes {@code esign.session_completed} from {@code pas.events} to flip
 * SIGNING → SIGNED / REVISION (registry §4, §9, PAY-07).
 */
@Component
public class EsignEventListener {

    private static final Logger log = LoggerFactory.getLogger(EsignEventListener.class);

    private final PaymentStatementRepository statementRepo;

    public EsignEventListener(PaymentStatementRepository statementRepo) {
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
        if (!"esign.session_completed".equals(eventType)) {
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
        String result = (String) payload.get("result");

        if (documentId == null || result == null) {
            throw new MalformedEventException("esign.session_completed missing document_id or result");
        }

        Optional<PaymentStatement> opt = statementRepo.findById(Long.parseLong(documentId));
        if (opt.isEmpty()) {
            log.warn("esign.session_completed for unknown statement {}", documentId);
            ack.acknowledge();
            return;
        }

        PaymentStatement stmt = opt.get();
        PaymentStatement.StatementStatus oldStatus = stmt.getStatus();

        // Only apply to SIGNING statements (idempotent on others)
        if (oldStatus != PaymentStatement.StatementStatus.SIGNING) {
            log.debug("Ignoring esign.session_completed for {} in status {}", stmt.getStatementNo(), oldStatus);
            ack.acknowledge();
            return;
        }

        PaymentStatement.StatementStatus newStatus = switch (result) {
            case "SIGNED" -> PaymentStatement.StatementStatus.SIGNED;
            case "FAILED", "CANCELLED" -> PaymentStatement.StatementStatus.REVISION; // PAY-07
            default -> {
                log.warn("Unknown esign result: {} for {}", result, stmt.getStatementNo());
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
        history.setTriggerKind(StatusHistory.TriggerKind.E);
        history.setTriggerRef((String) payload.get("session_id"));
        history.setOccurredAt(Instant.now());
        stmt.getStatusHistory().add(history);

        log.info("Statement {} status changed {} → {} by esign.session_completed",
            stmt.getStatementNo(), oldStatus, newStatus);
        ack.acknowledge();
    }
}
