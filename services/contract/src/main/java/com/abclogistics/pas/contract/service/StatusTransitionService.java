package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.domain.StatusHistory;
import com.abclogistics.pas.contract.domain.TriggerKind;
import com.abclogistics.pas.contract.error.FailedPreconditionException;
import com.abclogistics.pas.contract.repository.StatusHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * The single place a CONTRACT or ADDENDUM status changes (D17).
 *
 * <p>Validates the edge against {@link DocumentStatus#canTransitionTo}, appends exactly one
 * {@code status_history} row and records the audit event — in the caller's transaction, which is
 * why every method is {@code Propagation.MANDATORY}: a transition committed apart from the state
 * change it describes is precisely the drift D17 exists to make impossible.
 *
 * <p>Callers set the status column themselves; this service refuses to be the only writer because
 * the entity is the caller's aggregate. What it guarantees is that no legal transition happens
 * without a history row, and no illegal one happens at all.
 */
@Service
public class StatusTransitionService {

    private final StatusHistoryRepository history;
    private final AuditRecorder audit;

    public StatusTransitionService(StatusHistoryRepository history, AuditRecorder audit) {
        this.history = history;
        this.audit = audit;
    }

    /**
     * Applies one edge. Rejects an edge registry §9 has no row for.
     *
     * @param triggerRef workflow instance id, esign session id, or null for user/scheduler actions
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void transition(EntityType entityType, UUID entityId, String entityNo,
                           DocumentStatus from, DocumentStatus to,
                           TriggerKind trigger, UUID triggerRef, String note) {
        if (!from.canTransitionTo(to, trigger)) {
            throw new FailedPreconditionException(
                    "%s %s cannot move %s -> %s under trigger %s (registry §9)"
                            .formatted(entityType, entityNo, from, to, trigger));
        }
        record(entityType, entityId, entityNo, from, to, trigger, triggerRef, note);
    }

    /**
     * Applies the SUBMITTED → UNDER_REVIEW edge that a delayed {@code instance_started} skipped,
     * then the outcome edge — one history row each, one transaction (registry §9 footnote ¹).
     *
     * <p>Exists because Kafka orders sends, not commits: a {@code workflow.completed} can arrive
     * while the document is still SUBMITTED. Rejecting it would wedge the document permanently,
     * so the skipped edge is filled in rather than the event discarded.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void transitionOrderTolerant(EntityType entityType, UUID entityId, String entityNo,
                                        DocumentStatus current, DocumentStatus outcome,
                                        UUID instanceId) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    private void record(EntityType entityType, UUID entityId, String entityNo,
                        DocumentStatus from, DocumentStatus to,
                        TriggerKind trigger, UUID triggerRef, String note) {
        AuthenticatedUser actor = SecurityUtils.currentUser().orElse(null);
        history.save(StatusHistory.of(entityType, entityId, from, to, trigger, triggerRef,
                actor == null ? null : actor.userId(),
                actor == null ? "system" : actor.fullName(),
                note));
        audit.record(entityType.name(), entityId, entityNo, "STATUS_CHANGE",
                from == null ? null : from.name(), to.name(), note,
                Map.of("trigger", trigger.name()));
    }
}
