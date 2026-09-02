package com.abclogistics.pas.pricing.listener;

import com.abclogistics.pas.pricing.domain.ProcessedEvent;
import com.abclogistics.pas.pricing.repository.ProcessedEventRepository;
import com.abclogistics.pas.pricing.service.PriceListVersionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Consumes workflow.completed for PRICE_LIST documents and applies the outcome to the version
 * (SUBMITTED → APPROVED/REJECTED). Idempotent via processed_event. PRICE_LIST has no UNDER_REVIEW
 * state, so workflow.instance_started carries no transition and is ignored (§9).
 */
@Component
public class WorkflowEventListener {

    static final String COMPLETED = "workflow.completed";
    static final String DOCUMENT_TYPE = "PRICE_LIST";

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventListener.class);

    private final PriceListVersionService versionService;
    private final ProcessedEventRepository processed;
    private final ObjectMapper objectMapper;

    public WorkflowEventListener(PriceListVersionService versionService,
                                 ProcessedEventRepository processed, ObjectMapper objectMapper) {
        this.versionService = versionService;
        this.processed = processed;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @KafkaListener(topics = "pas.events", groupId = "pricing-service",
            autoStartup = "${pricing.kafka.listener-enabled:true}")
    public void onEvent(@Payload String payload,
                        @Header(name = "event_type", required = false) String eventType,
                        @Header(name = "document_type", required = false) String documentType,
                        @Header(name = "event_id", required = false) String eventId,
                        @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        if (!COMPLETED.equals(eventType) || !DOCUMENT_TYPE.equals(documentType)) {
            return;   // not ours: another service's event, or an instance_started (no-op for PRICE_LIST)
        }
        if (eventId == null) {
            throw new IllegalStateException("workflow.completed on pas.events has no event_id header, key=" + key);
        }
        UUID id = UUID.fromString(eventId);
        if (processed.existsById(id)) {
            log.debug("Event {} already applied, skipping", id);
            return;
        }
        processed.save(ProcessedEvent.of(id));

        JsonNode root = objectMapper.readTree(payload);
        String instanceId = text(root, "instance_id");
        versionService.applyWorkflowOutcome(UUID.fromString(key), text(root, "outcome"),
                instanceId == null ? null : UUID.fromString(instanceId));
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asString();
    }
}
