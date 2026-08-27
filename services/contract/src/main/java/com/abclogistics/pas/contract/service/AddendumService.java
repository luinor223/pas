package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.contract.domain.Addendum;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Addendum lifecycle (4.3). Same status machine and same D4 submit as a contract; the approval
 * chain may differ by configuration.
 */
@Service
public class AddendumService {

    @Transactional(readOnly = true)
    public Page<Addendum> search(UUID contractId, String status, Pageable pageable) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    @Transactional(readOnly = true)
    public Addendum get(UUID id) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    @Transactional
    public Addendum create(Addendum draft) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    @Transactional
    public Addendum update(UUID id, Addendum changes, int expectedVersion) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    @Transactional
    public void submit(UUID id) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    @Transactional
    public void cancel(UUID id, String reason) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    @Transactional
    public void revise(UUID id) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /**
     * Applies an approved addendum's effects to its parent contract, in the SAME transaction as
     * the addendum's own {@code APPROVED → ACTIVE} flip (registry §9 footnote ²):
     * {@code TERM_EXTENSION} sets {@code contract.validTo = newValidTo};
     * {@code PAYMENT_TERMS} sets {@code contract.paymentTerm = paymentTermOverride}.
     *
     * <p>This is a system action, audit-logged, and NOT a CTR-07 violation — it applies an already
     * approved addendum rather than editing terms directly.
     *
     * <p>{@code UNIT_PRICE_CHANGE} and {@code ADDED_SERVICE} apply nothing here: the former
     * carries no price data (D8) and the latter is record-only.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyEffectsToParent(Addendum addendum) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }
}
