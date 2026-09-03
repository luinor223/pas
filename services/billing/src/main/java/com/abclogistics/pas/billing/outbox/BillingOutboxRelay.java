package com.abclogistics.pas.billing.outbox;

import com.abclogistics.pas.billing.grpc.EsignGrpcClient;
import com.abclogistics.pas.billing.grpc.WorkflowGrpcClient;
import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRelay;
import com.abclogistics.pas.common.outbox.OutboxRelayProperties;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BillingOutboxRelay extends OutboxRelay {

    static final String START_REQUESTED = "workflow.start_requested";
    static final String ESIGN_REQUESTED = "esign.session_requested";
    static final String AUDIT_RECORDED = "audit.recorded";

    /**
     * The callee refused <em>this row</em> — same answer on every retry. NOT_FOUND is absent
     * (D4's dispatch window, §5.1); UNAUTHENTICATED / PERMISSION_DENIED / UNIMPLEMENTED describe
     * the deployment and recover on redeploy (§M2).
     */
    private static final Set<Status.Code> PERMANENT_STATUSES = EnumSet.of(
            Status.Code.FAILED_PRECONDITION, Status.Code.INVALID_ARGUMENT,
            Status.Code.OUT_OF_RANGE, Status.Code.ALREADY_EXISTS);

    private static final Logger log = LoggerFactory.getLogger(BillingOutboxRelay.class);

    private final AuditRecorder audit;
    private final KafkaTemplate<String, String> kafka;
    private final WorkflowGrpcClient workflow;
    private final EsignGrpcClient esign;
    private final ObjectMapper objectMapper;

    public BillingOutboxRelay(OutboxRepository outbox, OutboxRelayProperties props, TransactionTemplate tx,
                               KafkaTemplate<String, String> kafka, WorkflowGrpcClient workflow,
                               EsignGrpcClient esign, ObjectMapper objectMapper, AuditRecorder audit) {
        super(outbox, props, tx);
        this.kafka = kafka;
        this.workflow = workflow;
        this.esign = esign;
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    @Override
    protected void dispatch(OutboxEvent event) throws Exception {
        switch (event.getEventType()) {
            case AUDIT_RECORDED -> {
                kafka.send(kafkaRecord(event)).get(5, TimeUnit.SECONDS);
                log.debug("Published billing outbox {} type={} topic={}", event.getId(), event.getEventType(), event.topic());
            }
            case START_REQUESTED -> dispatchStartInstance(event);
            case ESIGN_REQUESTED -> dispatchCreateSigningSession(event);
            default -> throw new UnroutableEventException(
                    "Unroutable outbox event type '%s' (row %s) — add a dispatch branch"
                            .formatted(event.getEventType(), event.getId()));
        }
    }

    private void dispatchStartInstance(OutboxEvent event) {
        try {
            JsonNode p = objectMapper.readTree(event.getPayload());
            UUID idempotencyKey = UUID.fromString(p.get("idempotency_key").asString());
            UUID requestedById = uuidOrNull(text(p, "requested_by_id"));
            workflow.startInstance(
                idempotencyKey,
                p.get("document_type").asString(),
                UUID.fromString(p.get("document_id").asString()),
                p.get("document_no").asString(),
                text(p, "customer_name"),
                "NORMAL",
                requestedById, text(p, "requested_by_name"));
            log.debug("StartInstance dispatched for statement {}", p.get("document_id").asString());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
                return;
            }
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void dispatchCreateSigningSession(OutboxEvent event) {
        try {
            JsonNode p = objectMapper.readTree(event.getPayload());
            String idempotencyKey = p.get("idempotency_key").asString();
            esign.createSigningSession(
                p.get("document_type").asString(),
                p.get("document_id").asString(),
                p.get("document_no").asString(),
                text(p, "signer_name"),
                "",
                idempotencyKey);
            log.debug("CreateSigningSession dispatched for statement {}", p.get("document_id").asString());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
                return;
            }
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected String destination(OutboxEvent event) {
        return switch (event.getEventType()) {
            case START_REQUESTED -> "WorkflowInternal.StartInstance";
            case ESIGN_REQUESTED -> "EsignInternal.CreateSigningSession";
            default -> event.topic();
        };
    }

    @Override
    protected boolean isPermanentFailure(Exception e) {
        if (e instanceof StatusRuntimeException grpc) {
            return PERMANENT_STATUSES.contains(grpc.getStatus().getCode());
        }
        return e instanceof JacksonException || e instanceof UnroutableEventException;
    }

    static final class UnroutableEventException extends IllegalStateException {
        UnroutableEventException(String message) {
            super(message);
        }
    }

    @Override
    protected void onParked(OutboxEvent event, Exception cause) {
        if (AUDIT_RECORDED.equals(event.getEventType())) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("eventType", event.getEventType());
        detail.put("outboxRow", event.getId().toString());
        detail.put("attempts", event.getRetryCount());
        if (cause instanceof StatusRuntimeException grpc) {
            detail.put("grpcStatus", grpc.getStatus().getCode().name());
            detail.put("grpcDescription", grpc.getStatus().getDescription());
        } else {
            detail.put("error", cause.getMessage());
        }
        audit.record(event.getAggregateType(), event.getAggregateId(), documentNo(event),
                parkedAction(event.getEventType()), null, null,
                "Dispatch abandoned after a permanent refusal; it will not be retried",
                detail);
    }

    private static String parkedAction(String eventType) {
        return switch (eventType) {
            case START_REQUESTED -> "WORKFLOW_INITIALIZATION_FAILED";
            case ESIGN_REQUESTED -> "SEND_FOR_SIGNING_FAILED";
            default -> "DISPATCH_FAILED";
        };
    }

    private String documentNo(OutboxEvent event) {
        try {
            // billing payloads use snake_case document_no
            var node = objectMapper.readTree(event.getPayload()).get("document_no");
            if (node == null || node.isNull()) {
                node = objectMapper.readTree(event.getPayload()).get("documentNo");
            }
            return node == null || node.isNull() ? null : node.asString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String s = value.asString();
        return s.isEmpty() ? null : s;
    }

    private static UUID uuidOrNull(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
