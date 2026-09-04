package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.contract.domain.ApprovableDocument;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.domain.StatusHistory;
import com.abclogistics.pas.contract.domain.TriggerKind;
import com.abclogistics.pas.contract.repository.StatusHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The single place a status changes (D17): validates the edge, writes one status_history row,
 * and moves the document itself — the column and the timeline never part company.
 */
@Service
public class StatusTransitionService {

    private final StatusHistoryRepository history;
    private final AuditRecorder audit;

    public StatusTransitionService(StatusHistoryRepository history, AuditRecorder audit) {
        this.history = history;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void transition(ApprovableDocument document, DocumentStatus to,
                           TriggerKind trigger, UUID triggerRef, String note) {
        DocumentStatus from = document.getStatus();
        if (!from.canTransitionTo(to, trigger)) {
            throw new FailedPreconditionException(
                    "%s %s cannot move %s -> %s under trigger %s (registry §9)"
                            .formatted(document.entityType(), document.getDocumentNo(),
                                    from, to, trigger));
        }
        record(document.entityType(), document.getId(), document.getDocumentNo(),
                from, to, trigger, triggerRef, note);
        document.setStatus(to);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void transitionOrderTolerant(ApprovableDocument document, DocumentStatus outcome,
                                        UUID instanceId) {
        // a redelivery of an outcome already applied: not an error, not a second history row
        if (document.getStatus() == outcome) {
            return;
        }
        if (document.getStatus() == DocumentStatus.SUBMITTED) {
            // filled in, not skipped: §9 has no SUBMITTED -> APPROVED row to apply directly
            transition(document, DocumentStatus.UNDER_REVIEW, TriggerKind.W, instanceId,
                    "Approval instance started (applied out of order, registry §9 footnote 1)");
        }
        transition(document, outcome, TriggerKind.W, instanceId,
                "Approval %s".formatted(outcome.name().toLowerCase(Locale.ROOT)));
    }

    @Transactional(readOnly = true)
    public List<StatusHistory> history(EntityType entityType, UUID entityId) {
        return history.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(entityType, entityId);
    }

    private void record(EntityType entityType, UUID entityId, String entityNo,
                        DocumentStatus from, DocumentStatus to,
                        TriggerKind trigger, UUID triggerRef, String note) {
        history.save(StatusHistory.of(entityType, entityId, from, to, trigger, triggerRef,
                SecurityUtils.currentUserIdOrSystem(),
                SecurityUtils.currentUserNameOrSystem(),
                note));
        audit.record(entityType.name(), entityId, entityNo, "STATUS_CHANGE",
                from == null ? null : from.name(), to.name(), note,
                Map.of("trigger", trigger.name()));
    }
}
