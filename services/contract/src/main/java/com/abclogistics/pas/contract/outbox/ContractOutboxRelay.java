package com.abclogistics.pas.contract.outbox;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRelay;
import com.abclogistics.pas.common.outbox.OutboxRelayProperties;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.contract.event.EsignSessionRequested;
import com.abclogistics.pas.contract.event.WorkflowStartRequested;
import com.abclogistics.pas.contract.client.EsignGrpcClient;
import com.abclogistics.pas.contract.client.WorkflowGrpcClient;
import com.abclogistics.pas.contract.service.SigningRequestService;
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
     * The callee refused <em>this row</em> — the same answer on every retry. NOT_FOUND is absent
     * (D4's dispatch window, §5.1), and so are UNAUTHENTICATED / PERMISSION_DENIED / UNIMPLEMENTED:
     * those describe the deployment, and a redeploy recovers every row at once (§M2).
     */
    private static final Set<Status.Code> PERMANENT_STATUSES = EnumSet.of(
            Status.Code.FAILED_PRECONDITION, Status.Code.INVALID_ARGUMENT,
            Status.Code.OUT_OF_RANGE, Status.Code.ALREADY_EXISTS);

    private static final Logger log = LoggerFactory.getLogger(ContractOutboxRelay.class);

    private final AuditRecorder audit;
    private final KafkaTemplate<String, String> kafka;
    private final WorkflowGrpcClient workflow;
    private final EsignGrpcClient esign;
    private final ObjectMapper objectMapper;
    private final SigningRequestService signingRequests;

    public ContractOutboxRelay(OutboxRepository outbox, OutboxRelayProperties props,
                               KafkaTemplate<String, String> kafka, WorkflowGrpcClient workflow,
                               EsignGrpcClient esign, ObjectMapper objectMapper,
                               AuditRecorder audit, TransactionTemplate tx,
                               SigningRequestService signingRequests) {
        super(outbox, props, tx);
        this.audit = audit;
        this.kafka = kafka;
        this.workflow = workflow;
        this.esign = esign;
        this.objectMapper = objectMapper;
        this.signingRequests = signingRequests;
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
                payload.documentNo(), payload.signerName(), payload.signerEmail(),
                payload.customerName(), payload.requestedBy(), payload.requestedByName());
        signingRequests.associateSession(payload.documentType(), payload.documentId(),
                payload.idempotencyKey(), sessionId);
        log.debug("Created signing session {} for {} {} from outbox event {} (idempotencyKey={})",
                sessionId, payload.documentType(), payload.documentNo(), event.getId(),
                payload.idempotencyKey());
    }

    /** An answer, not an outage: UNAVAILABLE and friends stay retryable, which is what M2 is for. */
    @Override
    protected boolean isPermanentFailure(Exception e) {
        if (e instanceof StatusRuntimeException grpc) {
            return PERMANENT_STATUSES.contains(grpc.getStatus().getCode());
        }
        // an unparseable payload, or a type with no branch, is identical on every poll after this
        return e instanceof JacksonException || e instanceof UnroutableEventException;
    }

    /** No dispatcher for this event type. A deployment bug, and retrying it does not fix one. */
    static final class UnroutableEventException extends IllegalStateException {
        UnroutableEventException(String message) {
            super(message);
        }
    }

    /**
     * The user pressed a button and it is not going to happen. In the parking transaction so the
     * History tab shows it (D15): a SUBMITTED contract whose workflow never started otherwise
     * looks like one waiting its turn.
     */
    @Override
    protected void onParked(OutboxEvent event, Exception cause) {
        // audit rows are Kafka-bound and never permanent, so this cannot recurse
        if (AUDIT_RECORDED.equals(event.getEventType())) {
            return;
        }
        if (ESIGN_SESSION_REQUESTED.equals(event.getEventType())) {
            UUID requestKey = idempotencyKey(event);
            if (requestKey != null) {
                signingRequests.release(event.getAggregateType(), event.getAggregateId(), requestKey, null);
            }
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

    private UUID idempotencyKey(OutboxEvent event) {
        try {
            var node = objectMapper.readTree(event.getPayload()).get("idempotencyKey");
            return node == null || node.isNull() ? null : UUID.fromString(node.asString());
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
