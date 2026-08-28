package com.abclogistics.pas.contract.domain;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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

    /** One row of the §9 table: from -> to, legal only under these triggers. */
    private record Edge(DocumentStatus from, DocumentStatus to, Set<TriggerKind> triggers) { }

    private static final List<Edge> TABLE = List.of(
            new Edge(DRAFT, SUBMITTED, EnumSet.of(TriggerKind.U)),
            new Edge(DRAFT, CANCELLED, EnumSet.of(TriggerKind.U)),
            new Edge(SUBMITTED, CANCELLED, EnumSet.of(TriggerKind.U)),
            new Edge(SUBMITTED, UNDER_REVIEW, EnumSet.of(TriggerKind.W)),
            // registry §9's table listed only DRAFT and SUBMITTED, but §9 footnote 1 states the
            // M2 cancel keeps the document "SUBMITTED/UNDER_REVIEW until one branch definitively
            // resolves" and then "the owner sets CANCELLED". The two disagreed, and the table was
            // the half that could not be true: the handoff spans a gRPC round trip, during which
            // instance_started legitimately lands, so without this edge a cancel that workflow
            // has already accepted has nowhere to land and the instance is orphaned.
            //
            // RESTRICTED, and this table cannot express the restriction: the edge is reachable
            // ONLY through the M2 handoff, and only once WorkflowInternal.CancelInstance has
            // succeeded — that is, before any approval action was taken on the instance. It is
            // NOT an unconditional manual transition. ContractCancellationService is the sole
            // caller and enforces it: an actioned step comes back FAILED_PRECONDITION and fails
            // the cancel outright, an unresolved dispatch stays pending, and a document with no
            // dispatch intent to verify against is refused rather than cancelled locally. Any
            // new caller reaching this edge without that round trip is a bug.
            new Edge(UNDER_REVIEW, CANCELLED, EnumSet.of(TriggerKind.U)),
            new Edge(UNDER_REVIEW, APPROVED, EnumSet.of(TriggerKind.W)),
            new Edge(UNDER_REVIEW, REJECTED, EnumSet.of(TriggerKind.W)),
            new Edge(UNDER_REVIEW, REVISION_REQUESTED, EnumSet.of(TriggerKind.W)),
            new Edge(REVISION_REQUESTED, DRAFT, EnumSet.of(TriggerKind.U)),
            new Edge(REJECTED, DRAFT, EnumSet.of(TriggerKind.U)),
            new Edge(APPROVED, ACTIVE, EnumSet.of(TriggerKind.S)),
            new Edge(ACTIVE, EXPIRED, EnumSet.of(TriggerKind.S)),
            new Edge(ACTIVE, CANCELLED, EnumSet.of(TriggerKind.U)));

    /**
     * CTR-01 — "Chỉ được chỉnh sửa hợp đồng ở trạng thái Draft hoặc Revision Requested."
     * An app-level state check; a DB CHECK cannot see the transition (db-contract.md).
     *
     * <p>REJECTED is deliberately NOT editable: CTR-04 requires an explicit, audited revise
     * back to DRAFT first.
     */
    public boolean isEditable() {
        return this == DRAFT || this == REVISION_REQUESTED;
    }

    /**
     * True once the document can no longer move under any trigger.
     *
     * <p>Derived from the table rather than hardcoded, so a status that later gains an outgoing
     * edge stops reporting terminal automatically — the two can never disagree.
     */
    public boolean isTerminal() {
        return TABLE.stream().noneMatch(e -> e.from() == this);
    }

    /**
     * Is {@code from -> to} an edge registry §9 actually has, under this trigger?
     * The trigger is part of the key: {@code APPROVED -> ACTIVE} is legal for the D14d scheduler
     * (S) and illegal for a user (U), which is what stops CTR-03 being bypassed by hand.
     */
    public boolean canTransitionTo(DocumentStatus to, TriggerKind trigger) {
        return TABLE.stream().anyMatch(
                e -> e.from() == this && e.to() == to && e.triggers().contains(trigger));
    }
}
