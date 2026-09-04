package com.abclogistics.pas.pricing.outbox;

import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRelay;
import com.abclogistics.pas.common.outbox.OutboxRelayProperties;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.pricing.client.WorkflowGrpcClient;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * One relay, two transports: business/audit rows go to Kafka, the D4 workflow.start_requested row
 * dispatches to WorkflowInternal.StartInstance over gRPC (never the broker — nothing subscribes to
 * it). StartInstance is idempotent on idempotency_key, so a retry after a lost ack is safe.
 */
@Component
@ConditionalOnProperty(prefix = "outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PricingOutboxRelay extends OutboxRelay {

    static final String START_REQUESTED = "workflow.start_requested";

    private static final Logger log = LoggerFactory.getLogger(PricingOutboxRelay.class);

    private final KafkaTemplate<String, String> kafka;
    private final WorkflowGrpcClient workflow;
    private final ObjectMapper objectMapper;

    public PricingOutboxRelay(OutboxRepository outbox, OutboxRelayProperties props, TransactionTemplate tx,
                              KafkaTemplate<String, String> kafka, WorkflowGrpcClient workflow, ObjectMapper objectMapper) {
        super(outbox, props, tx);
        this.kafka = kafka;
        this.workflow = workflow;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void dispatch(OutboxEvent event) throws Exception {
        if (START_REQUESTED.equals(event.getEventType())) {
            dispatchStartInstance(event);
        } else {
            kafka.send(kafkaRecord(event)).get(5, TimeUnit.SECONDS);
            log.debug("Published pricing outbox {} type={} topic={}", event.getId(), event.getEventType(), event.topic());
        }
    }

    private void dispatchStartInstance(OutboxEvent event) {
        JsonNode p = objectMapper.readTree(event.getPayload());
        String requestedById = text(p, "requested_by");
        try {
            workflow.startInstance(
                    UUID.fromString(p.get("idempotency_key").asString()),
                    p.get("document_type").asString(),
                    UUID.fromString(p.get("document_id").asString()),
                    p.get("document_no").asString(),
                    text(p, "customer_name"),
                    text(p, "priority"),
                    requestedById == null ? null : UUID.fromString(requestedById),
                    text(p, "requested_by_name"));
            log.debug("StartInstance dispatched for version {}", p.get("document_id").asString());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
                return;   // a prior attempt's ack was lost; the instance already exists — done
            }
            throw e;
        }
    }

    @Override
    protected String destination(OutboxEvent event) {
        return START_REQUESTED.equals(event.getEventType()) ? "WorkflowInternal.StartInstance" : event.topic();
    }

    /** A malformed request will never succeed — park it instead of retrying for ever. */
    @Override
    protected boolean isPermanentFailure(Exception e) {
        return e instanceof StatusRuntimeException sre
                && sre.getStatus().getCode() == Status.Code.INVALID_ARGUMENT;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
