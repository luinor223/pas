package com.abclogistics.pas.contract.outbox;

import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRelay;
import com.abclogistics.pas.common.outbox.OutboxRelayProperties;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.contract.event.WorkflowStartRequested;
import com.abclogistics.pas.contract.service.WorkflowGrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Dual-dispatch relay — the one place contract-service departs from workflow-service's
 * Kafka-only relay.
 *
 * <p>Three event types share this outbox (registry §6), and they do NOT share a destination:
 * <ul>
 *   <li>{@code audit.recorded} → Kafka {@code pas.audit} (D15)</li>
 *   <li>{@code workflow.start_requested} → gRPC {@code WorkflowInternal.StartInstance} (D4) —
 *       nothing subscribes to it on Kafka; it is a retryable RPC parked in the outbox</li>
 *   <li>{@code esign.session_requested} → gRPC {@code EsignInternal.CreateSigningSession} (D10) —
 *       likewise unsubscribed</li>
 * </ul>
 *
 * <p>Publishing the two gRPC intents to Kafka would silently do nothing, so
 * {@link #dispatch} must discriminate on {@code eventType} and never fall through to Kafka
 * by default.
 */
@Component
@ConditionalOnProperty(prefix = "outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(type = "org.springframework.kafka.core.KafkaTemplate")
public class ContractOutboxRelay extends OutboxRelay {

    public static final String WORKFLOW_START_REQUESTED = "workflow.start_requested";
    public static final String ESIGN_SESSION_REQUESTED = "esign.session_requested";
    static final String AUDIT_RECORDED = "audit.recorded";

    private static final String GRPC_START_INSTANCE = "grpc:WorkflowInternal.StartInstance";
    private static final String GRPC_CREATE_SIGNING_SESSION = "grpc:EsignInternal.CreateSigningSession";

    private static final Logger log = LoggerFactory.getLogger(ContractOutboxRelay.class);

    private final KafkaTemplate<String, String> kafka;
    private final WorkflowGrpcClient workflow;
    private final ObjectMapper objectMapper;

    public ContractOutboxRelay(OutboxRepository outbox, OutboxRelayProperties props,
                               KafkaTemplate<String, String> kafka, WorkflowGrpcClient workflow,
                               ObjectMapper objectMapper) {
        super(outbox, props);
        this.kafka = kafka;
        this.workflow = workflow;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void dispatch(OutboxEvent event) throws Exception {
        switch (event.getEventType()) {
            case AUDIT_RECORDED -> publish(event);
            case WORKFLOW_START_REQUESTED -> startInstance(event);
            // The client lands with session 7's esign-service (item 13). Throwing parks the row for
            // a later retry; Kafka would accept it and no one would ever read it.
            case ESIGN_SESSION_REQUESTED -> throw new UnsupportedOperationException(
                    "esign.session_requested has no gRPC client yet; outbox row %s stays pending"
                            .formatted(event.getId()));
            // A new event type reaches production as a parked row and a loud log, not as a message
            // published to a topic nobody subscribes to.
            default -> throw new IllegalStateException(
                    "Unroutable outbox event type '%s' (row %s) — add a dispatch branch"
                            .formatted(event.getEventType(), event.getId()));
        }
    }

    /** Blocks on the {@code acks=all} ack, so {@code published_at} is stamped only after the broker confirms. */
    private void publish(OutboxEvent event) throws Exception {
        kafka.send(kafkaRecord(event)).get(5, TimeUnit.SECONDS);
        log.debug("Published outbox event {} type={} topic={} key={}", event.getId(),
                event.getEventType(), event.topic(), event.getAggregateId());
    }

    /**
     * The payload carries the key generated once at submit, so a retry after a lost ack resolves to
     * the instance that already exists instead of starting a second one — the row is re-read from
     * the outbox every attempt and never re-derived from the document, which may have moved on.
     */
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

    @Override
    protected String destination(OutboxEvent event) {
        return switch (event.getEventType()) {
            case WORKFLOW_START_REQUESTED -> GRPC_START_INSTANCE;
            case ESIGN_SESSION_REQUESTED -> GRPC_CREATE_SIGNING_SESSION;
            default -> event.topic();
        };
    }
}
