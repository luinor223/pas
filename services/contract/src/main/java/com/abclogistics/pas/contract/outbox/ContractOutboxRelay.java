package com.abclogistics.pas.contract.outbox;

import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRelay;
import com.abclogistics.pas.common.outbox.OutboxRelayProperties;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.contract.service.WorkflowGrpcClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

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

    private final KafkaTemplate<String, String> kafka;
    private final WorkflowGrpcClient workflow;

    public ContractOutboxRelay(OutboxRepository outbox, OutboxRelayProperties props,
                               KafkaTemplate<String, String> kafka, WorkflowGrpcClient workflow) {
        super(outbox, props);
        this.kafka = kafka;
        this.workflow = workflow;
    }

    @Override
    protected void dispatch(OutboxEvent event) throws Exception {
        throw new UnsupportedOperationException("session-3 Phase B");
    }
}
