package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.security.AuthenticatedUser;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.domain.StatusHistory;
import com.abclogistics.pas.contract.domain.TriggerKind;
import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.contract.repository.StatusHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** The single place a status changes (D17): validates the edge, writes one status_history row. */
@Service
public class StatusTransitionService {

    private final StatusHistoryRepository history;
    private final AuditRecorder audit;

    public StatusTransitionService(StatusHistoryRepository history, AuditRecorder audit) {
        this.history = history;
        this.audit = audit;
    }

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

    @Transactional(propagation = Propagation.MANDATORY)
    public void transitionOrderTolerant(EntityType entityType, UUID entityId, String entityNo,
                                        DocumentStatus current, DocumentStatus outcome,
                                        UUID instanceId) {
        // a redelivery of an outcome already applied: not an error, not a second history row
        if (current == outcome) {
            return;
        }
        DocumentStatus from = current;
        if (from == DocumentStatus.SUBMITTED) {
            // filled in, not skipped: §9 has no SUBMITTED -> APPROVED row to apply directly
            transition(entityType, entityId, entityNo, from, DocumentStatus.UNDER_REVIEW,
                    TriggerKind.W, instanceId,
                    "Approval instance started (applied out of order, registry §9 footnote 1)");
            from = DocumentStatus.UNDER_REVIEW;
        }
        transition(entityType, entityId, entityNo, from, outcome, TriggerKind.W, instanceId,
                "Approval %s".formatted(outcome.name().toLowerCase(java.util.Locale.ROOT)));
    }

    @Transactional(readOnly = true)
    public java.util.List<StatusHistory> history(EntityType entityType, UUID entityId) {
        return history.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(entityType, entityId);
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
