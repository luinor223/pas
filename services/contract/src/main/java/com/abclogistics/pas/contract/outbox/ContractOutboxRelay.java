package com.abclogistics.pas.contract.outbox;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRelay;
import com.abclogistics.pas.common.outbox.OutboxRelayProperties;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.contract.event.EsignSessionRequested;
import com.abclogistics.pas.contract.event.WorkflowStartRequested;
import com.abclogistics.pas.contract.service.EsignGrpcClient;
import com.abclogistics.pas.contract.service.WorkflowGrpcClient;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Dual-dispatch relay: audit.recorded to Kafka, the two gRPC intents to their stubs (registry §6). */
@Component
// the property is the only condition: @ConditionalOnBean(KafkaTemplate) evaluates before Kafka's
// auto-config outside auto-configuration, and dropped the gRPC dispatch along with the Kafka one
@ConditionalOnProperty(prefix = "outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ContractOutboxRelay extends OutboxRelay {

    public static final String WORKFLOW_START_REQUESTED = "workflow.start_requested";
    public static final String ESIGN_SESSION_REQUESTED = "esign.session_requested";
    static final String AUDIT_RECORDED = "audit.recorded";

    private static final String GRPC_START_INSTANCE = "grpc:WorkflowInternal.StartInstance";
    private static final String GRPC_CREATE_SIGNING_SESSION = "grpc:EsignInternal.CreateSigningSession";

    /**
     * The callee refused rather than failed. NOT_FOUND is deliberately absent: it is D4's dispatch
     * window on the workflow side, where the retry is the whole design (registry §5.1).
     */
    private static final Set<Status.Code> PERMANENT_STATUSES = EnumSet.of(
            Status.Code.FAILED_PRECONDITION, Status.Code.INVALID_ARGUMENT,
            Status.Code.PERMISSION_DENIED, Status.Code.UNAUTHENTICATED,
            Status.Code.UNIMPLEMENTED, Status.Code.OUT_OF_RANGE, Status.Code.ALREADY_EXISTS);

    private static final Logger log = LoggerFactory.getLogger(ContractOutboxRelay.class);

    private final AuditRecorder audit;
    private final KafkaTemplate<String, String> kafka;
    private final WorkflowGrpcClient workflow;
    private final EsignGrpcClient esign;
    private final ObjectMapper objectMapper;

    public ContractOutboxRelay(OutboxRepository outbox, OutboxRelayProperties props,
                               KafkaTemplate<String, String> kafka, WorkflowGrpcClient workflow,
                               EsignGrpcClient esign, ObjectMapper objectMapper,
                               AuditRecorder audit, TransactionTemplate tx) {
        super(outbox, props, tx);
        this.audit = audit;
        this.kafka = kafka;
        this.workflow = workflow;
        this.esign = esign;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void dispatch(OutboxEvent event) throws Exception {
        switch (event.getEventType()) {
            case AUDIT_RECORDED -> publish(event);
            case WORKFLOW_START_REQUESTED -> startInstance(event);
            case ESIGN_SESSION_REQUESTED -> createSigningSession(event);
            // A new event type parks loudly rather than publishing to a topic nobody reads.
            default -> throw new UnroutableEventException(
                    "Unroutable outbox event type '%s' (row %s) — add a dispatch branch"
                            .formatted(event.getEventType(), event.getId()));
        }
    }

    private void publish(OutboxEvent event) throws Exception {
        kafka.send(kafkaRecord(event)).get(5, TimeUnit.SECONDS);
        log.debug("Published outbox event {} type={} topic={} key={}", event.getId(),
                event.getEventType(), event.topic(), event.getAggregateId());
    }

    private void startInstance(OutboxEvent event) {
        WorkflowStartRequested payload =
                objectMapper.readValue(event.getPayload(), WorkflowStartRequested.class);
        UUID instanceId = workflow.startInstance(
                payload.idempotencyKey(), payload.documentType(), payload.documentId(),
                payload.documentNo(), payload.customerName(), payload.priority(),
                payload.requestedById(), payload.requestedByName());
        log.debug("Started workflow instance {} for {} {} from outbox event {} (idempotencyKey={})",
                instanceId, payload.documentType(), payload.documentNo(), event.getId(),
                payload.idempotencyKey());
    }

    private void createSigningSession(OutboxEvent event) {
        EsignSessionRequested payload =
                objectMapper.readValue(event.getPayload(), EsignSessionRequested.class);
        UUID sessionId = esign.createSigningSession(
                payload.idempotencyKey(), payload.documentType(), payload.documentId(),
                payload.documentNo(), payload.signerName(), payload.signerEmail());
        log.debug("Created signing session {} for {} {} from outbox event {} (idempotencyKey={})",
                sessionId, payload.documentType(), payload.documentNo(), event.getId(),
                payload.idempotencyKey());
    }

    /**
     * A gRPC status that is an answer, not an outage. Retrying these is pure noise: a double-send
     * refused with FAILED_PRECONDITION is refused identically on every poll, and at a five-second
     * interval that is a row re-claimed for the life of the deployment. UNAVAILABLE,
     * DEADLINE_EXCEEDED and the rest stay retryable — those are the outages the outbox exists for.
     */
    @Override
    protected boolean isPermanentFailure(Exception e) {
        if (e instanceof StatusRuntimeException grpc) {
            return PERMANENT_STATUSES.contains(grpc.getStatus().getCode());
        }
        // a payload this service cannot even parse, or a type it has no branch for, is in exactly
        // the same state on the next poll — and on every poll after that
        return e instanceof JacksonException || e instanceof UnroutableEventException;
    }

    /** No dispatcher for this event type. A deployment bug, and retrying it does not fix one. */
    static final class UnroutableEventException extends IllegalStateException {
        UnroutableEventException(String message) {
            super(message);
        }
    }

    /**
     * The user pressed a button and it is not going to happen. Written in the parking transaction
     * so the History tab carries the failure next to the action that started it (D15) — a
     * SUBMITTED contract whose workflow never started is otherwise indistinguishable from one
     * waiting its turn.
     */
    @Override
    protected void onParked(OutboxEvent event, Exception cause) {
        // audit rows are Kafka-bound and never classified permanent, so this cannot recurse
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
            case WORKFLOW_START_REQUESTED -> "WORKFLOW_INITIALIZATION_FAILED";
            case ESIGN_SESSION_REQUESTED -> "SEND_FOR_SIGNING_FAILED";
            default -> "DISPATCH_FAILED";
        };
    }

    /** Both dispatch payloads carry it; a row whose payload will not parse simply has none. */
    private String documentNo(OutboxEvent event) {
        try {
            var node = objectMapper.readTree(event.getPayload()).get("documentNo");
            return node == null || node.isNull() ? null : node.asString();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected String destination(OutboxEvent event) {
        return switch (event.getEventType()) {
            case WORKFLOW_START_REQUESTED -> GRPC_START_INSTANCE;
            case ESIGN_SESSION_REQUESTED -> GRPC_CREATE_SIGNING_SESSION;
            default -> event.topic();
        };
    }
}
