package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.domain.TriggerKind;
import com.abclogistics.pas.contract.repository.StatusHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The single place a CONTRACT or ADDENDUM status changes (D17).
 *
 * <p>Validates the edge against {@link DocumentStatus#canTransitionTo}, writes the status column
 * and appends exactly one {@code status_history} row — in the caller's transaction, which is why
 * every method is {@code Propagation.MANDATORY}: a transition committed outside the state change
 * it describes is precisely the drift D17 exists to make impossible.
 */
@Service
public class StatusTransitionService {

    private final StatusHistoryRepository history;

    public StatusTransitionService(StatusHistoryRepository history) {
        this.history = history;
    }

    /**
     * Applies one edge. Rejects an edge §9 has no row for.
     *
     * @param triggerRef workflow instance id, esign session id, or null for user/scheduler actions
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void transition(EntityType entityType, UUID entityId,
                           DocumentStatus from, DocumentStatus to,
                           TriggerKind trigger, UUID triggerRef, String note) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /**
     * Applies the SUBMITTED → UNDER_REVIEW edge that a delayed {@code instance_started} skipped,
     * then the outcome edge — one history row each, one transaction (registry §9 footnote ¹).
     *
     * <p>Exists because Kafka orders sends, not commits: a {@code workflow.completed} can arrive
     * while the document is still SUBMITTED. Rejecting it would wedge the document permanently.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void transitionOrderTolerant(EntityType entityType, UUID entityId,
                                        DocumentStatus current, DocumentStatus outcome,
                                        UUID instanceId) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }
}
