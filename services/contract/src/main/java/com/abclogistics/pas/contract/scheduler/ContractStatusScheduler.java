package com.abclogistics.pas.contract.scheduler;

import org.springframework.stereotype.Component;

/**
 * Date-driven transitions (D14d) and the expiry warning (D9).
 *
 * <p>All three sweeps are self-healing: a missed run is corrected by the next one, which is why
 * {@code document.expiring} is a DIRECT publish with no outbox row — a lost warning simply
 * re-fires, and an outbox row would only add a second copy.
 */
@Component
public class ContractStatusScheduler {

    /** CTR-05: APPROVED → ACTIVE on the effective date. Fires regardless of signing progress (D14e). */
    public void activateDueContracts() {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /** ACTIVE → EXPIRED past the end date. */
    public void expireEndedContracts() {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /**
     * APPROVED → ACTIVE for addenda reaching {@code effective_from}, applying their effects to the
     * parent contract in the same transaction (registry §9 footnote ²).
     */
    public void activateDueAddenda() {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /** D9: publishes {@code document.expiring} directly for contracts inside the warning window. */
    public void publishExpiryWarnings() {
        throw new UnsupportedOperationException("session-3 Phase B");
    }
}
