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
import com.abclogistics.pas.contract.service.SigningRequestService;
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

/** Consumes workflow lifecycle and signing-completion events. Idempotent and order-tolerant (§9¹). */
@Component
public class WorkflowEventListener {

    static final String INSTANCE_STARTED = "workflow.instance_started";
    static final String COMPLETED = "workflow.completed";
    static final String SIGNING_COMPLETED = "esign.session_completed";

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventListener.class);

    private final ContractRepository contracts;
    private final AddendumRepository addenda;
    private final StatusTransitionService transitions;
    private final ProcessedEventRepository processed;
    private final ObjectMapper objectMapper;
    private final SigningRequestService signingRequests;

    public WorkflowEventListener(ContractRepository contracts, AddendumRepository addenda,
                                 StatusTransitionService transitions,
                                 ProcessedEventRepository processed, ObjectMapper objectMapper,
                                 SigningRequestService signingRequests) {
        this.contracts = contracts;
        this.addenda = addenda;
        this.transitions = transitions;
        this.processed = processed;
        this.objectMapper = objectMapper;
        this.signingRequests = signingRequests;
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
        // ours before anything about its shape. pas.events carries every service's traffic, and
        // this listener owns only these document lifecycle types; skip everything else before parse
        if (!INSTANCE_STARTED.equals(type) && !COMPLETED.equals(type)
                && !SIGNING_COMPLETED.equals(type)) {
            return;
        }
        if (ownedType(documentType) == null) {
            return;   // a PRICE_LIST or PAYMENT_STATEMENT approval; another owner's document
        }
        if (eventId == null) {
            // Every owned event is outboxed and must carry event_id for consumer deduplication.
            throw new IllegalStateException("Record on pas.events has no event_id header, key=" + key);
        }
        UUID documentId = documentId(key);
        switch (type) {
            case INSTANCE_STARTED -> onInstanceStarted(payload, documentType, eventId, documentId);
            case COMPLETED -> onCompleted(payload, documentType, eventId, documentId);
            case SIGNING_COMPLETED -> onSigningCompleted(payload, documentType, eventId, documentId);
            default -> { }   // unreachable: the guard above already narrowed the type
        }
    }

    @Transactional
    public void onSigningCompleted(String payload, String documentType,
                                   String eventId, UUID documentId) {
        UUID id = UUID.fromString(eventId);
        if (processed.existsById(id)) {
            return;
        }
        processed.save(ProcessedEvent.of(id));
        UUID idempotencyKey = uuid(payload, "idempotency_key");
        UUID sessionId = uuid(payload, "session_id");
        if (idempotencyKey == null && sessionId == null) {
            throw new IllegalStateException(
                    "esign.session_completed has neither idempotency_key nor session_id");
        }
        signingRequests.release(documentType, documentId, idempotencyKey, sessionId);
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
        transitions.transition(document, DocumentStatus.UNDER_REVIEW, TriggerKind.W, instanceId,
                "Approval instance started");
    }

    @Transactional
    public void onCompleted(String payload, String documentType, String eventId, UUID documentId) {
        ApprovableDocument document = target(documentType, eventId, documentId);
        if (document == null) {
            return;
        }
        DocumentStatus outcome = outcomeOf(payload);
        UUID instanceId = uuid(payload, "instance_id");
        transitions.transitionOrderTolerant(document, outcome, instanceId);
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
