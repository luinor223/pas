package com.abclogistics.pas.esign.service;

import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.esign.domain.SigningSession;
import com.abclogistics.pas.esign.domain.SigningSession.SessionStatus;
import com.abclogistics.pas.esign.domain.StatusHistory;
import com.abclogistics.pas.esign.domain.StatusHistory.TriggerKind;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The single place a signing session's status changes (D17): validate the edge against the legal
 * table (registry §9), append the one status_history row, then move the column, so the status and
 * the timeline never part company. MANDATORY, so a transition can only happen inside a caller's
 * transaction.
 */
@Service
public class StatusTransitionService {

    @Transactional(propagation = Propagation.MANDATORY)
    public void transition(SigningSession session, SessionStatus to, TriggerKind trigger,
                           UUID actorId, String actorName, String note) {
        SessionStatus from = session.getStatus();
        if (!from.canTransitionTo(to, trigger)) {
            throw new FailedPreconditionException(
                    "Signing session %s cannot move %s -> %s under trigger %s (registry §9)"
                            .formatted(session.getSessionNo(), from, to, trigger));
        }
        session.addStatusHistory(StatusHistory.create(session, from.name(), to.name(), trigger, null,
                actorId, actorName, note));
        session.setStatus(to);
    }

    /** The opening row for a freshly created session (born PENDING_SEND): no prior status to validate. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void open(SigningSession session, UUID actorId, String actorName, String note) {
        session.addStatusHistory(StatusHistory.create(session, null, session.getStatus().name(),
                TriggerKind.U, null, actorId, actorName, note));
    }
}
