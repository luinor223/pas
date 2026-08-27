package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.contract.domain.Contract;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Contract lifecycle (4.2, CTR-01..CTR-07).
 */
@Service
public class ContractService {

    @Transactional(readOnly = true)
    public Page<Contract> search(UUID customerId, String status, Pageable pageable) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    @Transactional(readOnly = true)
    public Contract get(UUID id) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    @Transactional
    public Contract create(Contract draft) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /**
     * CTR-01: editable only in DRAFT or REVISION_REQUESTED, and {@code version} must match or the
     * optimistic lock rejects the write. Editing a REVISION_REQUESTED contract also flips it back
     * to DRAFT (registry §9's {@code REVISION_REQUESTED → DRAFT} edge) — there is no separate
     * action for that, which is why it is documented on the update endpoint.
     *
     * <p>CTR-07: a terms change on an APPROVED or ACTIVE contract is refused here and must go
     * through an addendum instead.
     */
    @Transactional
    public Contract update(UUID id, Contract changes, int expectedVersion) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /**
     * D4 commit-then-dispatch. In ONE transaction: CTR-02 checks, {@code DRAFT → SUBMITTED},
     * the {@code status_history} row, and a {@code workflow.start_requested} outbox row
     * carrying a freshly generated {@code idempotency_key}. A background relay then retries
     * {@code WorkflowInternal.StartInstance}.
     *
     * <p>Never the reverse order: a remote success followed by a failed local commit would orphan
     * a live, assignee-notified workflow instance on a document still DRAFT, and no retry can
     * undo that.
     *
     * <p>{@code ValidateStartable} is called as a read-only pre-check BEFORE the commit, so an
     * unconfigured document type fails fast instead of parking in the outbox forever.
     */
    @Transactional
    public void submit(UUID id) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /**
     * The M2 cancel-vs-dispatch handoff. A timestamp lease has no fencing token, so only a row
     * with {@code claimed_at IS NULL} may be cancelled directly; a stale claim is re-claimed and
     * its dispatch forced to completion before {@code CancelInstance} is called.
     *
     * <p>The document is never flipped to CANCELLED on an inconclusive read — it stays
     * SUBMITTED/UNDER_REVIEW until one branch definitively resolves, so there is no window where
     * a workflow instance starts after the document was already cancelled.
     */
    @Transactional
    public void cancel(UUID id, String reason) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /** CTR-04: a REJECTED contract is not silently editable; this is the audited opt-in back to DRAFT. */
    @Transactional
    public void revise(UUID id) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /**
     * Sends an APPROVED contract for e-signature (4.8, D10). Writes an
     * {@code esign.session_requested} outbox row with NO status change at all (D14e) — §5.5
     * forbids mixing approval state and signing state. The contract stays APPROVED, and
     * {@code APPROVED → ACTIVE} still fires purely on schedule regardless of signing progress.
     */
    @Transactional
    public void sendForSigning(UUID id) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }
}
