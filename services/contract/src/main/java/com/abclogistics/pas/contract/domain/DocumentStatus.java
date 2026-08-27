package com.abclogistics.pas.contract.domain;

/**
 * registry §3 — the CONTRACT / ADDENDUM status enum. Addenda share it exactly (db-contract.md).
 * <p>
 * There is deliberately no {@code SIGNING}/{@code SIGNED} (D14e, removed): requirement §5.5 forbids
 * mixing approval state with signing state, and the requirement's own contract diagram never had
 * them. Signing progress is composed in the UI from esign-service's {@code signing_session.status},
 * never persisted here.
 * <p>
 * <b>The transition table (registry §9), which {@link #canTransitionTo} must encode:</b>
 * <pre>
 *   DRAFT                    --U submit (CTR-02)--------------> SUBMITTED
 *   DRAFT, SUBMITTED         --U cancel-----------------------> CANCELLED
 *   SUBMITTED                --W first step activated---------> UNDER_REVIEW
 *   UNDER_REVIEW             --W APPROVED/REJECTED/REVISION---> APPROVED / REJECTED / REVISION_REQUESTED
 *   REVISION_REQUESTED       --U update-----------------------> DRAFT
 *   REJECTED                 --U revise (explicit, CTR-04)----> DRAFT
 *   APPROVED                 --S effective date (CTR-05)------> ACTIVE
 *   ACTIVE                   --S end date---------------------> EXPIRED
 *   ACTIVE                   --U controlled cancel (CTR-06)---> CANCELLED
 * </pre>
 * Every edge above writes exactly one {@code status_history} row in the same transaction as the
 * status column update (D17) — a status change with no history row is a bug.
 * <p>
 * <b>Order-tolerance (registry §9¹):</b> a {@code workflow.completed} that arrives while the
 * document is still {@code SUBMITTED} — possible when {@code instance_started} was delayed in the
 * outbox, since Kafka orders sends and not commits — must apply the skipped
 * {@code SUBMITTED -> UNDER_REVIEW} edge first and then the outcome, in one transaction, rather
 * than being rejected. This enum only answers "is this single edge legal"; the two-edge catch-up
 * is the consumer's job.
 */
public enum DocumentStatus {
    DRAFT,
    SUBMITTED,
    UNDER_REVIEW,
    APPROVED,
    ACTIVE,
    EXPIRED,
    REJECTED,
    REVISION_REQUESTED,
    CANCELLED;

    /**
     * CTR-01 — "Chỉ được chỉnh sửa hợp đồng ở trạng thái Draft hoặc Revision Requested."
     * An app-level state check; a DB CHECK cannot see the transition (db-contract.md).
     */
    public boolean isEditable() {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /** True once the document can no longer move under any trigger. */
    public boolean isTerminal() {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /**
     * Is {@code from -> to} an edge registry §9 actually has, under this trigger?
     * The trigger is part of the key: {@code APPROVED -> ACTIVE} is legal for the D14d scheduler
     * (S) and illegal for a user (U), which is what stops CTR-03 being bypassed by hand.
     */
    public boolean canTransitionTo(DocumentStatus to, TriggerKind trigger) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }
}
