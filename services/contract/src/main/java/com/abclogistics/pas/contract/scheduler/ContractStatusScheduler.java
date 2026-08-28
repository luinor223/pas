package com.abclogistics.pas.contract.scheduler;

import org.springframework.stereotype.Component;

/** Date-driven transitions (D14d) and the expiry warning (D9). Every sweep is self-healing. */
@Component
public class ContractStatusScheduler {

    public void activateDueContracts() {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    public void expireEndedContracts() {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    public void activateDueAddenda() {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    public void publishExpiryWarnings() {
        throw new UnsupportedOperationException("session-3 Phase B");
    }
}
