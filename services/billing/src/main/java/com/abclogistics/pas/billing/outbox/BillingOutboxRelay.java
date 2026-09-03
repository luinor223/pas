package com.abclogistics.pas.billing.outbox;

import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRelay;
import com.abclogistics.pas.common.outbox.OutboxRelayProperties;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.billing.grpc.EsignGrpcClient;
import com.abclogistics.pas.billing.grpc.WorkflowGrpcClient;
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

@Component
@ConditionalOnProperty(prefix = "outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BillingOutboxRelay extends OutboxRelay {

    static final String START_REQUESTED = "workflow.start_requested";
    static final String ESIGN_REQUESTED = "esign.session_requested";

    private static final Logger log = LoggerFactory.getLogger(BillingOutboxRelay.class);

    private final KafkaTemplate<String, String> kafka;
    private final WorkflowGrpcClient workflow;
    private final EsignGrpcClient esign;
    private final ObjectMapper objectMapper;

    public BillingOutboxRelay(OutboxRepository outbox, OutboxRelayProperties props, TransactionTemplate tx,
                               KafkaTemplate<String, String> kafka, WorkflowGrpcClient workflow,
                               EsignGrpcClient esign, ObjectMapper objectMapper) {
        super(outbox, props, tx);
        this.kafka = kafka;
        this.workflow = workflow;
        this.esign = esign;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void dispatch(OutboxEvent event) throws Exception {
        if (START_REQUESTED.equals(event.getEventType())) {
            dispatchStartInstance(event);
        } else if (ESIGN_REQUESTED.equals(event.getEventType())) {
            dispatchCreateSigningSession(event);
        } else {
            kafka.send(kafkaRecord(event)).get(5, TimeUnit.SECONDS);
            log.debug("Published billing outbox {} type={} topic={}", event.getId(), event.getEventType(), event.topic());
        }
    }

    private void dispatchStartInstance(OutboxEvent event) {
        try {
            JsonNode p = objectMapper.readTree(event.getPayload());
            UUID idempotencyKey = UUID.fromString(p.get("idempotency_key").asString());
            workflow.startInstance(
                idempotencyKey,
                p.get("document_type").asString(),
                UUID.fromString(p.get("document_id").asString()),
                p.get("document_no").asString(),
                text(p, "customer_name"),
                "NORMAL",
                null, null);
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
                idempotencyKey,
                text(p, "customer_name"),
                text(p, "requested_by"),
                text(p, "requested_by_name"));
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
        return e instanceof StatusRuntimeException sre
            && sre.getStatus().getCode() == Status.Code.INVALID_ARGUMENT;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
