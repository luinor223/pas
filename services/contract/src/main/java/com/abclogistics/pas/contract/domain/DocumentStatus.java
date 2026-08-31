package com.abclogistics.pas.contract.domain;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** registry §3 status enum, shared by contracts and addenda. {@link #TABLE} encodes §9. */
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

    private record Edge(DocumentStatus from, DocumentStatus to, Set<TriggerKind> triggers) { }

    private static final List<Edge> TABLE = List.of(
            new Edge(DRAFT, SUBMITTED, EnumSet.of(TriggerKind.U)),
            new Edge(DRAFT, CANCELLED, EnumSet.of(TriggerKind.U)),
            new Edge(SUBMITTED, CANCELLED, EnumSet.of(TriggerKind.U)),
            new Edge(SUBMITTED, UNDER_REVIEW, EnumSet.of(TriggerKind.W)),
            // §9's table omits this but §9¹ requires it: the M2 cancel spans a gRPC round trip
            // during which instance_started legitimately lands. RESTRICTED in a way this table
            // cannot express — reachable ONLY via the M2 handoff, once CancelInstance succeeded.
            // DocumentCancellationService is the sole caller; any other is a bug.
            new Edge(UNDER_REVIEW, CANCELLED, EnumSet.of(TriggerKind.U)),
            new Edge(UNDER_REVIEW, APPROVED, EnumSet.of(TriggerKind.W)),
            new Edge(UNDER_REVIEW, REJECTED, EnumSet.of(TriggerKind.W)),
            new Edge(UNDER_REVIEW, REVISION_REQUESTED, EnumSet.of(TriggerKind.W)),
            new Edge(REVISION_REQUESTED, DRAFT, EnumSet.of(TriggerKind.U)),
            new Edge(REJECTED, DRAFT, EnumSet.of(TriggerKind.U)),
            new Edge(APPROVED, ACTIVE, EnumSet.of(TriggerKind.S)),
            new Edge(ACTIVE, EXPIRED, EnumSet.of(TriggerKind.S)),
            new Edge(ACTIVE, CANCELLED, EnumSet.of(TriggerKind.U)));

    public boolean isEditable() {
        return this == DRAFT || this == REVISION_REQUESTED;
    }

    public boolean isTerminal() {
        return TABLE.stream().noneMatch(e -> e.from() == this);
    }

    public boolean canTransitionTo(DocumentStatus to, TriggerKind trigger) {
        return TABLE.stream().anyMatch(
                e -> e.from() == this && e.to() == to && e.triggers().contains(trigger));
    }
}
