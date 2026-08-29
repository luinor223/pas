package com.abclogistics.pas.contract.outbox;

import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRelay;
import com.abclogistics.pas.common.outbox.OutboxRelayProperties;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.contract.event.EsignSessionRequested;
import com.abclogistics.pas.contract.event.WorkflowStartRequested;
import com.abclogistics.pas.contract.service.EsignGrpcClient;
import com.abclogistics.pas.contract.service.WorkflowGrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

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

    private static final Logger log = LoggerFactory.getLogger(ContractOutboxRelay.class);

    private final KafkaTemplate<String, String> kafka;
    private final WorkflowGrpcClient workflow;
    private final EsignGrpcClient esign;
    private final ObjectMapper objectMapper;

    public ContractOutboxRelay(OutboxRepository outbox, OutboxRelayProperties props,
                               KafkaTemplate<String, String> kafka, WorkflowGrpcClient workflow,
                               EsignGrpcClient esign, ObjectMapper objectMapper,
                               TransactionTemplate tx) {
        super(outbox, props, tx);
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
            default -> throw new IllegalStateException(
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

    @Override
    protected String destination(OutboxEvent event) {
        return switch (event.getEventType()) {
            case WORKFLOW_START_REQUESTED -> GRPC_START_INSTANCE;
            case ESIGN_SESSION_REQUESTED -> GRPC_CREATE_SIGNING_SESSION;
            default -> event.topic();
        };
    }
}
