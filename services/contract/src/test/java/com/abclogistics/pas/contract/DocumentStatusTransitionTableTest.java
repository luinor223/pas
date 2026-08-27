package com.abclogistics.pas.contract;

import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.TriggerKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry §9 CONTRACT/ADDENDUM transition table, asserted edge by edge.
 * Pure domain logic — no Spring, no Docker.
 */
class DocumentStatusTransitionTableTest {

    @Test
    void everySanctionedEdgeIsAllowed() {
        assertThat(DocumentStatus.DRAFT.canTransitionTo(DocumentStatus.SUBMITTED, TriggerKind.U)).isTrue();
        assertThat(DocumentStatus.DRAFT.canTransitionTo(DocumentStatus.CANCELLED, TriggerKind.U)).isTrue();
        assertThat(DocumentStatus.SUBMITTED.canTransitionTo(DocumentStatus.CANCELLED, TriggerKind.U)).isTrue();
        assertThat(DocumentStatus.SUBMITTED.canTransitionTo(DocumentStatus.UNDER_REVIEW, TriggerKind.W)).isTrue();
        assertThat(DocumentStatus.UNDER_REVIEW.canTransitionTo(DocumentStatus.APPROVED, TriggerKind.W)).isTrue();
        assertThat(DocumentStatus.UNDER_REVIEW.canTransitionTo(DocumentStatus.REJECTED, TriggerKind.W)).isTrue();
        assertThat(DocumentStatus.UNDER_REVIEW.canTransitionTo(DocumentStatus.REVISION_REQUESTED, TriggerKind.W)).isTrue();
        assertThat(DocumentStatus.REVISION_REQUESTED.canTransitionTo(DocumentStatus.DRAFT, TriggerKind.U)).isTrue();
        assertThat(DocumentStatus.REJECTED.canTransitionTo(DocumentStatus.DRAFT, TriggerKind.U)).isTrue();
        assertThat(DocumentStatus.APPROVED.canTransitionTo(DocumentStatus.ACTIVE, TriggerKind.S)).isTrue();
        assertThat(DocumentStatus.ACTIVE.canTransitionTo(DocumentStatus.EXPIRED, TriggerKind.S)).isTrue();
        assertThat(DocumentStatus.ACTIVE.canTransitionTo(DocumentStatus.CANCELLED, TriggerKind.U)).isTrue();
    }

    @Test
    void ctr03ForbidsDraftStraightToApproved() {
        // CTR-03: a contract must go through the approval workflow. No trigger kind may shortcut it.
        for (TriggerKind trigger : TriggerKind.values()) {
            assertThat(DocumentStatus.DRAFT.canTransitionTo(DocumentStatus.APPROVED, trigger))
                    .withFailMessage("DRAFT -> APPROVED must be impossible, trigger %s", trigger)
                    .isFalse();
        }
    }

    @Test
    void revisionRequestedNeverGoesStraightBackToSubmitted() {
        // The edge is REVISION_REQUESTED -> DRAFT (by being edited), then DRAFT -> SUBMITTED.
        // There is no direct edge, and inventing one would skip the CTR-02 checks.
        for (TriggerKind trigger : TriggerKind.values()) {
            assertThat(DocumentStatus.REVISION_REQUESTED.canTransitionTo(DocumentStatus.SUBMITTED, trigger))
                    .withFailMessage("REVISION_REQUESTED -> SUBMITTED is not a §9 edge, trigger %s", trigger)
                    .isFalse();
        }
    }

    @Test
    void terminalStatesHaveNoOutgoingEdges() {
        for (DocumentStatus terminal : new DocumentStatus[] {
                DocumentStatus.EXPIRED, DocumentStatus.CANCELLED }) {
            assertThat(terminal.isTerminal()).isTrue();
            for (DocumentStatus to : DocumentStatus.values()) {
                for (TriggerKind trigger : TriggerKind.values()) {
                    assertThat(terminal.canTransitionTo(to, trigger))
                            .withFailMessage("%s is terminal but allowed -> %s (%s)", terminal, to, trigger)
                            .isFalse();
                }
            }
        }
    }

    @Test
    void ctr01EditableOnlyInDraftAndRevisionRequested() {
        assertThat(DocumentStatus.DRAFT.isEditable()).isTrue();
        assertThat(DocumentStatus.REVISION_REQUESTED.isEditable()).isTrue();

        for (DocumentStatus s : new DocumentStatus[] {
                DocumentStatus.SUBMITTED, DocumentStatus.UNDER_REVIEW, DocumentStatus.APPROVED,
                DocumentStatus.ACTIVE, DocumentStatus.EXPIRED, DocumentStatus.REJECTED,
                DocumentStatus.CANCELLED }) {
            assertThat(s.isEditable())
                    .withFailMessage("CTR-01: %s must not be editable", s).isFalse();
        }
    }

    @Test
    void ctr04RejectedIsNotEditableWithoutExplicitRevise() {
        // CTR-04: a rejected document is not silently editable. Reaching DRAFT is an explicit,
        // audited action -- which is exactly why REJECTED itself reports not-editable.
        assertThat(DocumentStatus.REJECTED.isEditable()).isFalse();
        assertThat(DocumentStatus.REJECTED.canTransitionTo(DocumentStatus.DRAFT, TriggerKind.U)).isTrue();
    }

    @Test
    void schedulerOnlyEdgesRejectUserTriggers() {
        // CTR-05 / D14d: APPROVED -> ACTIVE and ACTIVE -> EXPIRED are date-driven. A user must not
        // be able to activate or expire a document by hand.
        assertThat(DocumentStatus.APPROVED.canTransitionTo(DocumentStatus.ACTIVE, TriggerKind.U)).isFalse();
        assertThat(DocumentStatus.ACTIVE.canTransitionTo(DocumentStatus.EXPIRED, TriggerKind.U)).isFalse();
    }

    @Test
    void expiredDoesNotRenewInSessionThree() {
        // Deferred decision: EXPIRED -> ACTIVE (renewal of an already-expired contract via an
        // approved TERM_EXTENSION addendum) is NOT a §9 edge yet. This test pins the current
        // contract so the follow-up that adds the edge has to update the registry and this test
        // together, rather than the behaviour drifting in silently.
        for (TriggerKind trigger : TriggerKind.values()) {
            assertThat(DocumentStatus.EXPIRED.canTransitionTo(DocumentStatus.ACTIVE, trigger)).isFalse();
        }
    }
}
