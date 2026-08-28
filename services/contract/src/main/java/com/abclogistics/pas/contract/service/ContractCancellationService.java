package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.ForbiddenException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRelayProperties;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.contract.domain.Contract;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.domain.TriggerKind;
import com.abclogistics.pas.contract.event.WorkflowStartRequested;
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

/**
 * The M2 cancel-vs-dispatch handoff, in its own bean because it is the one flow in this service
 * that must span several transactions.
 *
 * <p>The problem it solves: the relay's claim is a timestamp lease with <b>no fencing token</b>.
 * "Claimed a while ago" does not mean "the claimer is dead" — it may be a worker paused by a GC or
 * a stalled VM that will wake up and call {@code StartInstance} moments later. So a stale claim is
 * never cancelled; it is taken over and driven to completion, and only then is the instance it
 * created cancelled.
 *
 * <pre>
 * 1. UPDATE outbox SET cancelled_at = now() WHERE id = ? AND claimed_at IS NULL
 *      1 row  -> nobody ever attempted dispatch -> CANCELLED, done
 *      0 rows -> the row is claimed (fresh or stale, treated identically) -> step 2
 * 2. CancelInstance
 *      OK                  -> CANCELLED, done
 *      FAILED_PRECONDITION -> a step was already actioned; the cancel fails outright
 *      NOT_FOUND           -> INCONCLUSIVE, not terminal
 *          fresh claim -> back off, the caller retries from step 1
 *          stale claim -> re-claim, call StartInstance ourselves with the row's stored
 *                         idempotency_key, stamp published_at, then retry step 2
 * </pre>
 *
 * <p>Throughout, the document keeps its current status. It is never flipped to CANCELLED on an
 * inconclusive read — that is exactly what closes the window in which a workflow instance could
 * start against a document that was already cancelled. It may legitimately move from SUBMITTED to
 * UNDER_REVIEW while the handoff is in flight, which is why {@link #applyCancellation} re-reads
 * the status inside the writing transaction and why registry §9 carries the UNDER_REVIEW ->
 * CANCELLED edge its footnote 1 always implied.
 *
 * <p>Transaction boundaries are explicit rather than annotated: the forced dispatch has to commit
 * {@code published_at} on its own, before the second {@code CancelInstance} is attempted, and a
 * gRPC round trip must not be made while holding the connection that will write the cancellation.
 */
@Service
public class ContractCancellationService {

    /** {@code CANCELLED} maps to 200, {@code PENDING} to 202 — the caller retries the same call. */
    public enum Outcome { CANCELLED, PENDING }

    /** CTR-06: cancelling a live contract is its own permission, not part of {@code contract:write}. */
    public static final String CANCEL_ACTIVE = "contract:cancel_active";

    private static final Logger log = LoggerFactory.getLogger(ContractCancellationService.class);

    private final ContractRepository contracts;
    private final OutboxRepository outbox;
    private final StatusTransitionService transitions;
    private final WorkflowGrpcClient workflow;
    private final OutboxRelayProperties relayProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public ContractCancellationService(ContractRepository contracts, OutboxRepository outbox,
                                       StatusTransitionService transitions,
                                       WorkflowGrpcClient workflow,
                                       OutboxRelayProperties relayProperties,
                                       ObjectMapper objectMapper, TransactionTemplate tx) {
        this.contracts = contracts;
        this.outbox = outbox;
        this.transitions = transitions;
        this.workflow = workflow;
        this.relayProperties = relayProperties;
        this.objectMapper = objectMapper;
        this.tx = tx;
    }

    public Outcome cancel(UUID id, String reason) {
        DocumentStatus status = tx.execute(s -> requireCancellable(id));

        // SUBMITTED may have a dispatch in flight; UNDER_REVIEW certainly has a live instance.
        // Both go through the handoff. DRAFT never had a dispatch intent and ACTIVE's instance
        // completed long ago, so for those there is no race to resolve and the cancel commits
        // directly.
        if (status == DocumentStatus.SUBMITTED || status == DocumentStatus.UNDER_REVIEW) {
            return handoff(id, reason);
        }
        tx.executeWithoutResult(s -> applyCancellation(id, reason));
        return Outcome.CANCELLED;
    }

    /** @return the current status, once it is established that a user may cancel from it. */
    private DocumentStatus requireCancellable(UUID id) {
        Contract contract = contracts.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract %s not found".formatted(id)));
        DocumentStatus status = contract.getStatus();

        // Checked here and not with @PreAuthorize because the rule depends on the document's
        // state: the same endpoint cancels a DRAFT under contract:write alone.
        if (status == DocumentStatus.ACTIVE && !SecurityUtils.hasPermission(CANCEL_ACTIVE)) {
            throw new ForbiddenException(
                    "Cancelling an ACTIVE contract requires %s (CTR-06)".formatted(CANCEL_ACTIVE));
        }
        if (!status.canTransitionTo(DocumentStatus.CANCELLED, TriggerKind.U)) {
            throw new ConflictException(
                    "Contract %s is %s and cannot be cancelled (registry §9)"
                            .formatted(contract.getContractNo(), status));
        }
        return status;
    }

    private Outcome handoff(UUID id, String reason) {
        OutboxEvent row = latestStartRequest(id);
        if (row == null) {
            // No dispatch intent to race, and no stored idempotency key either — CancelInstance is
            // keyed on it, so there is nothing this service could ask workflow-service about.
            tx.executeWithoutResult(s -> applyCancellation(id, reason));
            return Outcome.CANCELLED;
        }

        // Step 1 — no staleness clause. Only a row nobody ever touched may be cancelled outright,
        // and it is cancelled in the same transaction as the document so the two cannot disagree.
        Boolean cancelledOutright = tx.execute(s -> {
            if (outbox.cancelIfNotClaimed(row.getId(), Instant.now()) != 1) {
                return false;
            }
            applyCancellation(id, reason);
            return true;
        });
        if (Boolean.TRUE.equals(cancelledOutright)) {
            log.debug("Cancelled contract {} by cancelling its unclaimed outbox row {}", id, row.getId());
            return Outcome.CANCELLED;
        }

        return afterDispatchAttempted(id, row.getId(), reason);
    }

    /** Step 2, and the forced-dispatch branch that step 2's inconclusive answer leads to. */
    private Outcome afterDispatchAttempted(UUID id, UUID rowId, String reason) {
        OutboxEvent row = reload(rowId);
        UUID idempotencyKey = payloadOf(row).idempotencyKey();

        CancelOutcome first = workflow.cancelInstance(id, ContractService.DOCUMENT_TYPE, idempotencyKey);
        if (first != CancelOutcome.NOT_FOUND) {
            return resolve(id, reason, first);
        }

        // INCONCLUSIVE. A published row means the instance exists and workflow-service simply has
        // not caught up; a still-fresh claim means the dispatch may land at any moment. Neither is
        // a licence to cancel the document.
        if (row.getPublishedAt() != null || !isStale(row)) {
            log.debug("Cancel of contract {} inconclusive (published={}, claimedAt={}), staying pending",
                    id, row.getPublishedAt(), row.getClaimedAt());
            return Outcome.PENDING;
        }

        // The claim is stale. Take it over and finish the dispatch ourselves — the permanent
        // UNIQUE on workflow_instance.idempotency_key makes the original worker's later call, if
        // it ever wakes up, a harmless no-op against the very instance we are about to cancel.
        if (!forceDispatch(row)) {
            return Outcome.PENDING;
        }
        return resolve(id, reason,
                workflow.cancelInstance(id, ContractService.DOCUMENT_TYPE, idempotencyKey));
    }

    private Outcome resolve(UUID id, String reason, CancelOutcome outcome) {
        return switch (outcome) {
            case CANCELLED -> {
                tx.executeWithoutResult(s -> applyCancellation(id, reason));
                yield Outcome.CANCELLED;
            }
            case ALREADY_ACTIONED -> throw new ConflictException(
                    "A workflow step for contract %s was already actioned; it can no longer be cancelled (M2)"
                            .formatted(id));
            // Still nothing after we forced the dispatch: not terminal, so the document stays put.
            case NOT_FOUND -> Outcome.PENDING;
        };
    }

    /** @return false when the takeover lost its race, which is a reason to wait, not to cancel. */
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
            // Release the claim exactly as the relay's failure path does, so the row is retried
            // immediately instead of waiting out another full lease.
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

    /**
     * Re-reads the status inside the writing transaction rather than trusting the one read in
     * {@link #requireCancellable}: the handoff spans a gRPC round trip, and the document may have
     * moved to UNDER_REVIEW meanwhile. An edge registry §9 has no row for then fails here.
     */
    private void applyCancellation(UUID id, String reason) {
        Contract contract = contracts.findById(id)
                .orElseThrow(() -> new NotFoundException("Contract %s not found".formatted(id)));
        DocumentStatus before = contract.getStatus();
        transitions.transition(EntityType.CONTRACT, contract.getId(), contract.getContractNo(),
                before, DocumentStatus.CANCELLED, TriggerKind.U, null,
                RequestValues.blankToNull(reason));
        contract.setStatus(DocumentStatus.CANCELLED);
    }

    private boolean isStale(OutboxEvent row) {
        Instant claimedAt = row.getClaimedAt();
        return claimedAt != null && claimedAt.isBefore(Instant.now().minus(relayProperties.claimLease()));
    }

    private OutboxEvent latestStartRequest(UUID contractId) {
        List<OutboxEvent> rows = tx.execute(s -> outbox.findForAggregate(
                contractId, WorkflowStartRequested.EVENT_TYPE, Limit.of(1)));
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
