package com.abclogistics.pas.common.outbox;

import java.time.Duration;

/**
 * Relay tuning — corresponds to mechanics.md M2 claim-lease window.
 * <p>
 * One publisher per service must poll {@code ORDER BY created_at} and claim with
 * {@code WHERE published_at IS NULL AND cancelled_at IS NULL AND (claimed_at IS NULL OR claimed_at < now() - lease)}.
 * The lease has no fencing (timestamp only), so a stale claim is never treated as cancellable —
 * only {@code claimed_at IS NULL} rows may be cancelled directly; stale rows are re-claimed and forced to dispatch.
 */
public class OutboxRelayProperties {

    private boolean enabled = true;
    private Duration claimLease = Duration.ofSeconds(60);
    private int batchSize = 100;
    private Duration pollInterval = Duration.ofSeconds(5);

    public boolean isEnabled() {
        return enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getClaimLease() {
        return claimLease;
    }

    public Duration claimLease() {
        return claimLease;
    }

    public void setClaimLease(Duration claimLease) {
        this.claimLease = claimLease;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int batchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public Duration pollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }
}
