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

/**
 * Consumes {@code workflow.instance_started} and {@code workflow.completed} from
 * {@code pas.events} (group {@code contract-service}), discriminating on the
 * {@code document_type} header so records for other owners are skipped before deserialization.
 *
 * <p>Both of this service's document types are handled. An addendum starts its own workflow
 * instance under document type {@code ADDENDUM} (4.3), so consuming only {@code CONTRACT} would
 * leave every submitted addendum stuck at SUBMITTED for ever — never reviewed, never approved,
 * and therefore never activated to apply its effects to the parent.
 *
 * <p>Every handler is idempotent via {@code processed_event}, inserted in the same transaction
 * as the effect: offsets commit after processing, so a mid-batch death re-reads records that were
 * already applied.
 *
 * <p>Order-tolerant by design (registry §9 footnote ¹): a {@code workflow.completed} that arrives
 * while the document is still SUBMITTED applies the skipped {@code SUBMITTED → UNDER_REVIEW}
 * edge first and then the outcome, in one transaction, one history row each. Kafka orders sends,
 * not commits, so this is a reachable ordering and not a defensive nicety — rejecting it would
 * wedge the document permanently.
 */
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

    /**
     * One listener, dispatched on {@code event_type}. {@code pas.events} carries every service's
     * events, so the great majority of records are not ours and are dropped on the header alone.
     *
     * <p>{@code documentId} comes from the record key, not the payload: {@code workflow.completed}
     * carries no document id of its own, and the key is what actually orders a document's events
     * within a partition.
     *
     * <p>The transaction starts here rather than on each handler, so that the dedup row and the
     * effect it guards commit or roll back together even though the handlers are reached by a
     * plain call rather than through the proxy.
     */
    @Transactional
    @KafkaListener(topics = "pas.events", groupId = "contract-service",
            autoStartup = "${contract.kafka.listener-enabled:true}")
    public void onEvent(@Payload String payload,
                        @Header(name = "event_type", required = false) String eventType,
                        @Header(name = "document_type", required = false) String documentType,
                        @Header(name = "event_id", required = false) String eventId,
                        @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        String type = eventType == null ? "" : eventType;
        // Decide it is ours BEFORE demanding anything of its shape. pas.events carries every
        // service's traffic, including the three direct-published events (D9) that have no outbox
        // row and therefore no event_id at all — insisting on the header first would dead-letter
        // perfectly good records this service was never meant to read.
        if (!INSTANCE_STARTED.equals(type) && !COMPLETED.equals(type)) {
            return;
        }
        if (ownedType(documentType) == null) {
            return;   // a PRICE_LIST or PAYMENT_STATEMENT approval; another owner's document
        }
        if (eventId == null) {
            // Now it IS ours, and without the header a redelivery is indistinguishable from a new
            // event. Applying it would risk a duplicate transition, so it goes to the DLT.
            throw new IllegalStateException("Record on pas.events has no event_id header, key=" + key);
        }
        UUID documentId = documentId(key);
        switch (type) {
            case INSTANCE_STARTED -> onInstanceStarted(payload, documentType, eventId, documentId);
            case COMPLETED -> onCompleted(payload, documentType, eventId, documentId);
            default -> { }   // unreachable: the guard above already narrowed the type
        }
    }

    /** The record key is the document id (registry §4). A record of ours without one is malformed. */
    private static UUID documentId(String key) {
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("Record key is not a document id: " + key, e);
        }
    }

    /** {@code workflow.instance_started} → SUBMITTED → UNDER_REVIEW, for either document type. */
    @Transactional
    public void onInstanceStarted(String payload, String documentType, String eventId, UUID documentId) {
        ApprovableDocument document = target(documentType, eventId, documentId);
        if (document == null) {
            return;
        }
        if (document.getStatus() != DocumentStatus.SUBMITTED) {
            // completed already arrived and filled this edge in. Recording the dedup row and doing
            // nothing else is the whole point of order tolerance.
            log.debug("instance_started for {} ignored: already {}", documentId, document.getStatus());
            return;
        }
        UUID instanceId = uuid(payload, "instance_id");
        transitions.transition(document.entityType(), document.getId(), document.getDocumentNo(),
                DocumentStatus.SUBMITTED, DocumentStatus.UNDER_REVIEW, TriggerKind.W, instanceId,
                "Approval instance started");
        document.setStatus(DocumentStatus.UNDER_REVIEW);
    }

    /** {@code workflow.completed} → APPROVED / REJECTED / REVISION_REQUESTED. */
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

    /**
     * The three guards every handler shares: is it ours, have we already applied it, and does the
     * document still exist. Claiming the event id here — in the handler's transaction — is what
     * makes a rolled-back effect release its claim too.
     *
     * @return the document to act on, or null when there is nothing to do.
     */
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
            // Nothing to apply and nothing to retry: a document this database never had. The
            // dedup row stops it coming back round the partition for ever.
            log.warn("Workflow event {} references unknown {} {}", id, type, documentId);
        }
        return document;
    }

    /** The document types this service owns, or null for another owner's (registry §4). */
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
