package com.abclogistics.pas.billing.service;

import com.abclogistics.pas.billing.domain.PaymentStatement;
import com.abclogistics.pas.billing.domain.PaymentStatement.StatementStatus;
import com.abclogistics.pas.billing.domain.StatusHistory;
import com.abclogistics.pas.billing.domain.StatusHistory.TriggerKind;
import com.abclogistics.pas.billing.repository.StatusHistoryRepository;
import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.common.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The single place a payment statement's status changes (D17): validates the edge against the
 * StatementStatus table, writes one status_history row, and moves the column, so the column and the
 * timeline never part company.
 */
@Service
public class StatusTransitionService {

    private final StatusHistoryRepository history;

    public StatusTransitionService(StatusHistoryRepository history) {
        this.history = history;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void transition(PaymentStatement statement, StatementStatus to,
                           TriggerKind trigger, String triggerRef) {
        StatementStatus from = statement.getStatus();
        if (from == null || !from.canTransitionTo(to, trigger)) {
            throw new FailedPreconditionException(
                    "Payment statement %s cannot move %s -> %s under trigger %s (registry §9)"
                            .formatted(statement.getStatementNo(), from, to, trigger));
        }
        record(statement, from, to, trigger, triggerRef);
        statement.setStatus(to);
    }

    /** The creation row for a new statement (from = null): there is no edge to validate. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void open(PaymentStatement statement) {
        record(statement, null, statement.getStatus(), TriggerKind.U, null);
    }

    private void record(PaymentStatement statement, StatementStatus from, StatementStatus to,
                        TriggerKind trigger, String triggerRef) {
        StatusHistory h = new StatusHistory();
        h.setStatement(statement);
        h.setFromStatus(from == null ? null : from.name());
        h.setToStatus(to.name());
        h.setTriggerKind(trigger);
        h.setTriggerRef(triggerRef);
        h.setActorId(SecurityUtils.currentUserIdOrSystem());
        h.setActorName(SecurityUtils.currentUserNameOrSystem());
        h.setOccurredAt(Instant.now());
        history.save(h);
        statement.getStatusHistory().add(h);
    }
}
