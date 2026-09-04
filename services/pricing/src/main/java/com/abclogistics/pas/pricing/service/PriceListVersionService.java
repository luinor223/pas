package com.abclogistics.pas.pricing.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.outbox.OutboxEvent;
import com.abclogistics.pas.common.outbox.OutboxRepository;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.common.security.SystemActor;
import com.abclogistics.pas.pricing.domain.PriceList;
import com.abclogistics.pas.pricing.domain.PriceListVersion;
import com.abclogistics.pas.pricing.domain.PriceListVersionStatus;
import com.abclogistics.pas.pricing.domain.StatusHistory;
import com.abclogistics.pas.pricing.domain.TriggerKind;
import com.abclogistics.pas.pricing.repository.PriceListRepository;
import com.abclogistics.pas.pricing.repository.PriceListVersionRepository;
import com.abclogistics.pas.pricing.repository.StatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Version lifecycle. submit follows D4: the workflow pre-check runs outside the transaction, then a
 * single local transaction flips DRAFT → SUBMITTED and writes the workflow.start_requested outbox
 * row; the dispatcher calls StartInstance afterwards. The workflow outcome (APPROVED/REJECTED) is
 * applied by the Kafka consumer via {@link #applyWorkflowOutcome}.
 */
@Service
public class PriceListVersionService {

    static final String DOCUMENT_TYPE = "PRICE_LIST";
    static final String ENTITY = "PRICE_LIST_VERSION";
    static final String START_REQUESTED = "workflow.start_requested";

    private static final Logger log = LoggerFactory.getLogger(PriceListVersionService.class);

    private final PriceListVersionRepository versions;
    private final PriceListRepository lists;
    private final StatusHistoryRepository history;
    private final OutboxRepository outbox;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper;
    private final WorkflowGrpcClient workflow;
    private final TransactionTemplate tx;

    public PriceListVersionService(PriceListVersionRepository versions, PriceListRepository lists,
                                   StatusHistoryRepository history, OutboxRepository outbox,
                                   AuditRecorder audit, ObjectMapper objectMapper,
                                   WorkflowGrpcClient workflow, TransactionTemplate tx) {
        this.versions = versions;
        this.lists = lists;
        this.history = history;
        this.outbox = outbox;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.workflow = workflow;
        this.tx = tx;
    }

    /**
     * D4 submit. Not @Transactional: the remote pre-check must not run inside the local transaction,
     * or a successful start with a failed local write would orphan a live instance on a DRAFT version.
     */
    public void submit(UUID versionId) {
        PriceListVersion peek = versions.findById(versionId)
                .orElseThrow(() -> new NotFoundException("No price list version " + versionId));
        requireStatus(peek, PriceListVersionStatus.DRAFT, "submit");

        workflow.validateStartable(DOCUMENT_TYPE);   // outside the transaction

        AuthenticatedUser actor = SecurityUtils.currentUser().orElse(null);
        tx.executeWithoutResult(status -> {
            PriceListVersion version = versions.findById(versionId)
                    .orElseThrow(() -> new NotFoundException("No price list version " + versionId));
            requireStatus(version, PriceListVersionStatus.DRAFT, "submit");
            PriceList list = lists.findById(version.getPriceListId())
                    .orElseThrow(() -> new NotFoundException("No price list " + version.getPriceListId()));
            assertNoBlockingOverlap(version);

            transition(version, PriceListVersionStatus.SUBMITTED, TriggerKind.U, null, "Submitted for approval");

            UUID idempotencyKey = UUID.randomUUID();
            outbox.save(OutboxEvent.event(START_REQUESTED, DOCUMENT_TYPE, version.getId(),
                    startRequestedPayload(list, version, idempotencyKey, actor)));
        });
    }

    /** PRC-06: a REJECTED version can be revised back to DRAFT and resubmitted. */
    @Transactional
    public void revise(UUID versionId) {
        PriceListVersion version = versions.findById(versionId)
                .orElseThrow(() -> new NotFoundException("No price list version " + versionId));
        requireStatus(version, PriceListVersionStatus.REJECTED, "revise");
        transition(version, PriceListVersionStatus.DRAFT, TriggerKind.U, null, "Revised after rejection");
    }

    /** Applies a workflow.completed outcome (SUBMITTED → APPROVED/REJECTED). Runs in the consumer's
     *  transaction. Order-tolerant: a non-SUBMITTED version means it was already applied — skip. */
    public void applyWorkflowOutcome(UUID versionId, String outcome, UUID instanceId) {
        PriceListVersion version = versions.findById(versionId).orElse(null);
        if (version == null) {
            log.warn("workflow.completed for unknown price list version {}", versionId);
            return;
        }
        if (version.getStatus() != PriceListVersionStatus.SUBMITTED) {
            log.debug("workflow.completed for {} ignored: already {}", versionId, version.getStatus());
            return;
        }
        if ("APPROVED".equals(outcome)) {
            truncateOverlappingPredecessors(version);   // §9³, before the APPROVED flip trips EXCLUDE
            transition(version, PriceListVersionStatus.APPROVED, TriggerKind.W, instanceId, "Workflow outcome APPROVED");
        } else {
            transition(version, PriceListVersionStatus.REJECTED, TriggerKind.W, instanceId, "Workflow outcome " + outcome);
        }
    }

    /** Scheduler: APPROVED → EFFECTIVE at valid_from, superseding the effective predecessor (PRC-04). */
    @Transactional
    public void activate(UUID versionId) {
        PriceListVersion version = versions.findById(versionId)
                .orElseThrow(() -> new NotFoundException("No price list version " + versionId));
        requireStatus(version, PriceListVersionStatus.APPROVED, "activate");
        for (PriceListVersion predecessor :
                versions.effectivePredecessors(version.getScopeKey(), version.getId(), version.getValidFrom())) {
            transition(predecessor, PriceListVersionStatus.SUPERSEDED, TriggerKind.S, version.getId(),
                    "Superseded by v" + version.getVersionNo());
        }
        transition(version, PriceListVersionStatus.EFFECTIVE, TriggerKind.S, null, "Became effective");
    }

    /** Stamps a version as warned after the D9 warning is acked, so it does not re-fire next sweep. */
    @Transactional
    public void markExpiryWarned(UUID versionId) {
        versions.findById(versionId).ifPresent(v -> v.setExpiryWarnedAt(java.time.Instant.now()));
    }

    /** Scheduler: EFFECTIVE → EXPIRED after valid_to. */
    @Transactional
    public void expire(UUID versionId) {
        PriceListVersion version = versions.findById(versionId)
                .orElseThrow(() -> new NotFoundException("No price list version " + versionId));
        requireStatus(version, PriceListVersionStatus.EFFECTIVE, "expire");
        transition(version, PriceListVersionStatus.EXPIRED, TriggerKind.S, null, "Validity ended");
    }

    /** Rejects a submit that overlaps a peer we cannot truncate (one starting on/after this version's
     *  valid_from). A true predecessor (earlier valid_from) is fine — it is truncated at approval. */
    private void assertNoBlockingOverlap(PriceListVersion version) {
        for (PriceListVersion peer : versions.overlapping(
                version.getScopeKey(), version.getId(), version.getValidFrom(), version.getValidTo())) {
            if (!peer.getValidFrom().isBefore(version.getValidFrom())) {
                throw new ConflictException("Validity overlaps an existing effective version of the same scope (PRC-03)");
            }
        }
    }

    /** Truncates each overlapping predecessor's valid_to to the day before this version starts, in the
     *  same transaction, so the successor's APPROVED flip does not trip the PRC-03 EXCLUDE constraint. */
    private void truncateOverlappingPredecessors(PriceListVersion successor) {
        java.time.LocalDate cutoff = successor.getValidFrom().minusDays(1);
        for (PriceListVersion predecessor : versions.overlapping(
                successor.getScopeKey(), successor.getId(), successor.getValidFrom(), successor.getValidTo())) {
            if (predecessor.getValidFrom().isBefore(successor.getValidFrom())
                    && predecessor.getValidTo().isAfter(cutoff)) {
                predecessor.setValidTo(cutoff);
                history.save(new StatusHistory(predecessor.getId(), predecessor.getStatus(), predecessor.getStatus(),
                        TriggerKind.S, successor.getId(), "Truncated by successor v" + successor.getVersionNo(),
                        SecurityUtils.currentUserIdOrSystem()));
            }
        }
    }

    /** Sets the status, appends the history row, and writes the audit record — one transaction. */
    void transition(PriceListVersion version, PriceListVersionStatus to, TriggerKind kind, UUID ref, String note) {
        PriceListVersionStatus from = version.getStatus();
        version.setStatus(to);
        history.save(new StatusHistory(version.getId(), from, to, kind, ref, note,
                SecurityUtils.currentUserIdOrSystem()));
        audit.record(ENTITY, version.getId(), "STATUS_CHANGE", from.name(), to.name(), note, Map.of());
    }

    private String startRequestedPayload(PriceList list, PriceListVersion version,
                                         UUID idempotencyKey, AuthenticatedUser actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("document_type", DOCUMENT_TYPE);
        payload.put("document_id", version.getId().toString());
        payload.put("document_no", list.getPriceListNo() + " v" + version.getVersionNo());
        payload.put("idempotency_key", idempotencyKey.toString());
        payload.put("priority", "NORMAL");
        payload.put("customer_name", "");   // pricing holds customer_id only, no name snapshot
        payload.put("requested_by_id", actor == null ? SystemActor.ID.toString() : actor.userId().toString());
        payload.put("requested_by_name", actor == null ? SystemActor.NAME : actor.fullName());
        return objectMapper.writeValueAsString(payload);
    }

    private void requireStatus(PriceListVersion version, PriceListVersionStatus expected, String action) {
        if (version.getStatus() != expected) {
            throw new ConflictException("Cannot " + action + " a " + version.getStatus() + " version (needs " + expected + ")");
        }
    }
}
