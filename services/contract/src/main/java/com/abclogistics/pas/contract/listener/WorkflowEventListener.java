package com.abclogistics.pas.contract.listener;

import com.abclogistics.pas.contract.domain.ApprovableDocument;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.domain.ProcessedEvent;
import com.abclogistics.pas.contract.domain.TriggerKind;
import com.abclogistics.pas.contract.repository.AddendumRepository;
import com.abclogistics.pas.contract.repository.ContractRepository;
import com.abclogistics.pas.contract.repository.ProcessedEventRepository;
import com.abclogistics.pas.contract.service.StatusTransitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/** Consumes workflow.instance_started / workflow.completed. Idempotent, and order-tolerant (§9¹). */
@Component
public class WorkflowEventListener {

    static final String INSTANCE_STARTED = "workflow.instance_started";
    static final String COMPLETED = "workflow.completed";

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventListener.class);

    private final ContractRepository contracts;
    private final AddendumRepository addenda;
    private final StatusTransitionService transitions;
    private final ProcessedEventRepository processed;
    private final ObjectMapper objectMapper;

    public WorkflowEventListener(ContractRepository contracts, AddendumRepository addenda,
                                 StatusTransitionService transitions,
                                 ProcessedEventRepository processed, ObjectMapper objectMapper) {
        this.contracts = contracts;
        this.addenda = addenda;
        this.transitions = transitions;
        this.processed = processed;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @KafkaListener(topics = "pas.events", groupId = "contract-service",
            autoStartup = "${contract.kafka.listener-enabled:true}")
    public void onEvent(@Payload String payload,
                        @Header(name = "event_type", required = false) String eventType,
                        @Header(name = "document_type", required = false) String documentType,
                        @Header(name = "event_id", required = false) String eventId,
                        @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        String type = eventType == null ? "" : eventType;
        // ours before anything about its shape: D9 direct-published events carry no event_id
        if (!INSTANCE_STARTED.equals(type) && !COMPLETED.equals(type)) {
            return;
        }
        if (ownedType(documentType) == null) {
            return;   // a PRICE_LIST or PAYMENT_STATEMENT approval; another owner's document
        }
        if (eventId == null) {
            // ours, but without the header a redelivery looks identical to a new event
            throw new IllegalStateException("Record on pas.events has no event_id header, key=" + key);
        }
        UUID documentId = documentId(key);
        switch (type) {
            case INSTANCE_STARTED -> onInstanceStarted(payload, documentType, eventId, documentId);
            case COMPLETED -> onCompleted(payload, documentType, eventId, documentId);
            default -> { }   // unreachable: the guard above already narrowed the type
        }
    }

    private static UUID documentId(String key) {
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("Record key is not a document id: " + key, e);
        }
    }

    @Transactional
    public void onInstanceStarted(String payload, String documentType, String eventId, UUID documentId) {
        ApprovableDocument document = target(documentType, eventId, documentId);
        if (document == null) {
            return;
        }
        if (document.getStatus() != DocumentStatus.SUBMITTED) {
            // completed already arrived and filled this edge in; record the dedup row only
            log.debug("instance_started for {} ignored: already {}", documentId, document.getStatus());
            return;
        }
        UUID instanceId = uuid(payload, "instance_id");
        transitions.transition(document.entityType(), document.getId(), document.getDocumentNo(),
                DocumentStatus.SUBMITTED, DocumentStatus.UNDER_REVIEW, TriggerKind.W, instanceId,
                "Approval instance started");
        document.setStatus(DocumentStatus.UNDER_REVIEW);
    }

    @Transactional
    public void onCompleted(String payload, String documentType, String eventId, UUID documentId) {
        ApprovableDocument document = target(documentType, eventId, documentId);
        if (document == null) {
            return;
        }
        DocumentStatus outcome = outcomeOf(payload);
        UUID instanceId = uuid(payload, "instance_id");
        transitions.transitionOrderTolerant(document.entityType(), document.getId(),
                document.getDocumentNo(), document.getStatus(), outcome, instanceId);
        document.setStatus(outcome);
    }

    private ApprovableDocument target(String documentType, String eventId, UUID documentId) {
        EntityType type = ownedType(documentType);
        if (type == null) {
            return null;
        }
        UUID id = UUID.fromString(eventId);
        if (processed.existsById(id)) {
            log.debug("Event {} already applied, skipping", id);
            return null;
        }
        processed.save(ProcessedEvent.of(id));

        ApprovableDocument document = switch (type) {
            case CONTRACT -> contracts.findById(documentId).orElse(null);
            case ADDENDUM -> addenda.findById(documentId).orElse(null);
        };
        if (document == null) {
            // a document this database never had; the dedup row stops it recurring
            log.warn("Workflow event {} references unknown {} {}", id, type, documentId);
        }
        return document;
    }

    private static EntityType ownedType(String documentType) {
        for (EntityType type : EntityType.values()) {
            if (type.name().equals(documentType)) {
                return type;
            }
        }
        return null;
    }

    private DocumentStatus outcomeOf(String payload) {
        String outcome = text(payload, "outcome");
        try {
            return DocumentStatus.valueOf(outcome);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException(
                    "workflow.completed carries an outcome this service has no status for: " + outcome);
        }
    }

    private UUID uuid(String payload, String field) {
        String value = text(payload, field);
        return value == null ? null : UUID.fromString(value);
    }

    private String text(String payload, String field) {
        JsonNode node = objectMapper.readTree(payload).get(field);
        return node == null || node.isNull() || node.asString().isEmpty() ? null : node.asString();
    }
}
