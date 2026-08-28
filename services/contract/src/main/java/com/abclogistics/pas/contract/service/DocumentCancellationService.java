package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.ForbiddenException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRelayProperties;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.contract.domain.ApprovableDocument;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.domain.TriggerKind;
import com.abclogistics.pas.contract.event.WorkflowStartRequested;
import com.abclogistics.pas.contract.repository.AddendumRepository;
import com.abclogistics.pas.contract.repository.ContractRepository;
import com.abclogistics.pas.contract.service.WorkflowGrpcClient.CancelOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The M2 cancel-vs-dispatch handoff, and the only legitimate route to UNDER_REVIEW -> CANCELLED. */
@Service
public class DocumentCancellationService {

    public enum Outcome { CANCELLED, PENDING }

    public static final String CANCEL_ACTIVE = "contract:cancel_active";

    private static final Logger log = LoggerFactory.getLogger(DocumentCancellationService.class);

    private final ContractRepository contracts;
    private final AddendumRepository addenda;
    private final OutboxRepository outbox;
    private final StatusTransitionService transitions;
    private final WorkflowGrpcClient workflow;
    private final OutboxRelayProperties relayProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public DocumentCancellationService(ContractRepository contracts, AddendumRepository addenda,
                                       OutboxRepository outbox,
                                       StatusTransitionService transitions,
                                       WorkflowGrpcClient workflow,
                                       OutboxRelayProperties relayProperties,
                                       ObjectMapper objectMapper, TransactionTemplate tx) {
        this.contracts = contracts;
        this.addenda = addenda;
        this.outbox = outbox;
        this.transitions = transitions;
        this.workflow = workflow;
        this.relayProperties = relayProperties;
        this.objectMapper = objectMapper;
        this.tx = tx;
    }

    public Outcome cancel(EntityType type, UUID id, String reason) {
        DocumentStatus status = tx.execute(s -> requireCancellable(type, id));

        // SUBMITTED/UNDER_REVIEW race a dispatch, so go through the handoff; the rest commit directly
        if (status == DocumentStatus.SUBMITTED || status == DocumentStatus.UNDER_REVIEW) {
            return handoff(type, id, reason, status);
        }
        tx.executeWithoutResult(s -> applyCancellation(type, id, reason));
        return Outcome.CANCELLED;
    }

    private DocumentStatus requireCancellable(EntityType type, UUID id) {
        ApprovableDocument document = load(type, id);
        DocumentStatus status = document.getStatus();

        // not @PreAuthorize: the same endpoint cancels a DRAFT under contract:write alone
        if (status == DocumentStatus.ACTIVE && !SecurityUtils.hasPermission(CANCEL_ACTIVE)) {
            throw new ForbiddenException(
                    "Cancelling an ACTIVE %s requires %s (CTR-06)"
                            .formatted(type.name().toLowerCase(java.util.Locale.ROOT), CANCEL_ACTIVE));
        }
        if (!status.canTransitionTo(DocumentStatus.CANCELLED, TriggerKind.U)) {
            throw new ConflictException(
                    "%s %s is %s and cannot be cancelled (registry §9)"
                            .formatted(type, document.getDocumentNo(), status));
        }
        return status;
    }

    private ApprovableDocument load(EntityType type, UUID id) {
        java.util.Optional<? extends ApprovableDocument> found = switch (type) {
            case CONTRACT -> contracts.findById(id);
            case ADDENDUM -> addenda.findById(id);
        };
        return found.orElseThrow(() -> new NotFoundException("%s %s not found".formatted(type, id)));
    }

    private Outcome handoff(EntityType type, UUID id, String reason, DocumentStatus status) {
        OutboxEvent row = latestStartRequest(id);
        if (row == null) {
            // no dispatch intent means no idempotency key, and CancelInstance is keyed on it
            if (status == DocumentStatus.UNDER_REVIEW) {
                // a live instance: cancelling locally would strand it with assignees on it
                throw new ConflictException(
                        ("%s %s is UNDER_REVIEW but has no dispatch intent to cancel against; "
                         + "its workflow instance cannot be cancelled from here (registry §9¹)")
                                .formatted(type, id));
            }
            tx.executeWithoutResult(s -> applyCancellation(type, id, reason));
            return Outcome.CANCELLED;
        }

        // step 1, no staleness clause: only an untouched row is cancelled outright, in the
        // same transaction as the document
        Boolean cancelledOutright = tx.execute(s -> {
            if (outbox.cancelIfNotClaimed(row.getId(), Instant.now()) != 1) {
                return false;
            }
            applyCancellation(type, id, reason);
            return true;
        });
        if (Boolean.TRUE.equals(cancelledOutright)) {
            log.debug("Cancelled {} {} by cancelling its unclaimed outbox row {}", type, id, row.getId());
            return Outcome.CANCELLED;
        }

        return afterDispatchAttempted(type, id, row.getId(), reason);
    }

    private Outcome afterDispatchAttempted(EntityType type, UUID id, UUID rowId, String reason) {
        OutboxEvent row = reload(rowId);
        UUID idempotencyKey = payloadOf(row).idempotencyKey();

        CancelOutcome first = workflow.cancelInstance(id, type.name(), idempotencyKey);
        if (first != CancelOutcome.NOT_FOUND) {
            return resolve(type, id, reason, first);
        }

        // INCONCLUSIVE: workflow may not have caught up, or the dispatch may land at any moment
        if (row.getPublishedAt() != null || !isStale(row)) {
            log.debug("Cancel of {} {} inconclusive (published={}, claimedAt={}), staying pending",
                    type, id, row.getPublishedAt(), row.getClaimedAt());
            return Outcome.PENDING;
        }

        // stale: take it over and finish the dispatch; the UNIQUE on idempotency_key makes the
        // original worker's later call a harmless no-op
        if (!forceDispatch(row)) {
            return Outcome.PENDING;
        }
        return resolve(type, id, reason, workflow.cancelInstance(id, type.name(), idempotencyKey));
    }

    private Outcome resolve(EntityType type, UUID id, String reason, CancelOutcome outcome) {
        return switch (outcome) {
            case CANCELLED -> {
                tx.executeWithoutResult(s -> applyCancellation(type, id, reason));
                yield Outcome.CANCELLED;
            }
            case ALREADY_ACTIONED -> throw new ConflictException(
                    "A workflow step for %s %s was already actioned; it can no longer be cancelled (M2)"
                            .formatted(type, id));
            // still nothing after the forced dispatch: not terminal, so the document stays put
            case NOT_FOUND -> Outcome.PENDING;
        };
    }

    private boolean forceDispatch(OutboxEvent row) {
        Instant now = Instant.now();
        Instant staleThreshold = now.minus(relayProperties.claimLease());
        Boolean claimed = tx.execute(s -> outbox.claim(row.getId(), now, staleThreshold) == 1);
        if (!Boolean.TRUE.equals(claimed)) {
            log.debug("Lost the re-claim of stale outbox row {}; leaving it to the relay", row.getId());
            return false;
        }

        WorkflowStartRequested payload = payloadOf(row);
        try {
            workflow.startInstance(payload.idempotencyKey(), payload.documentType(),
                    payload.documentId(), payload.documentNo(), payload.customerName(),
                    payload.priority(), payload.requestedById(), payload.requestedByName());
        } catch (RuntimeException e) {
            // released as the relay's failure path does, so the row is retried immediately
            log.warn("Forced dispatch of outbox row {} failed: {}", row.getId(), e.getMessage());
            tx.executeWithoutResult(s -> outbox.findById(row.getId()).ifPresent(stale -> {
                stale.releaseClaim();
                outbox.save(stale);
            }));
            return false;
        }

        tx.executeWithoutResult(s -> outbox.findById(row.getId()).ifPresent(e -> {
            e.markPublished();
            outbox.save(e);
        }));
        log.info("Forced the stale dispatch of outbox row {} to completion before cancelling", row.getId());
        return true;
    }

    private void applyCancellation(EntityType type, UUID id, String reason) {
        ApprovableDocument document = load(type, id);
        DocumentStatus before = document.getStatus();
        transitions.transition(type, document.getId(), document.getDocumentNo(),
                before, DocumentStatus.CANCELLED, TriggerKind.U, null,
                RequestValues.blankToNull(reason));
        document.setStatus(DocumentStatus.CANCELLED);
    }

    private boolean isStale(OutboxEvent row) {
        Instant claimedAt = row.getClaimedAt();
        return claimedAt != null && claimedAt.isBefore(Instant.now().minus(relayProperties.claimLease()));
    }

    private OutboxEvent latestStartRequest(UUID documentId) {
        List<OutboxEvent> rows = tx.execute(s -> outbox.findForAggregate(
                documentId, WorkflowStartRequested.EVENT_TYPE, Limit.of(1)));
        return rows == null || rows.isEmpty() ? null : rows.getFirst();
    }

    private OutboxEvent reload(UUID rowId) {
        OutboxEvent row = tx.execute(s -> outbox.findById(rowId).orElse(null));
        if (row == null) {
            throw new ConflictException("Outbox row %s vanished mid-cancel".formatted(rowId));
        }
        return row;
    }

    private WorkflowStartRequested payloadOf(OutboxEvent row) {
        return objectMapper.readValue(row.getPayload(), WorkflowStartRequested.class);
    }
}
