package com.abclogistics.pas.common.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Relay tuning — corresponds to mechanics.md M2 claim-lease window.
 * <p>
 * One publisher per service must poll {@code ORDER BY created_at} and claim with
 * {@code WHERE published_at IS NULL AND cancelled_at IS NULL AND (claimed_at IS NULL OR claimed_at < now() - lease)}.
 * The lease has no fencing (timestamp only), so a stale claim is never treated as cancellable —
 * only {@code claimed_at IS NULL} rows may be cancelled directly; stale rows are re-claimed and forced to dispatch.
 */
@ConfigurationProperties(prefix = "outbox.relay")
public record OutboxRelayProperties(
        boolean enabled,
        Duration claimLease,
        int batchSize,
        Duration pollInterval
) {
    public OutboxRelayProperties {
        if (claimLease == null) claimLease = Duration.ofSeconds(60);
        if (pollInterval == null) pollInterval = Duration.ofSeconds(5);
        if (batchSize <= 0) batchSize = 100;
    }

    public OutboxRelayProperties() {
        this(true, Duration.ofSeconds(60), 100, Duration.ofSeconds(5));
    }
}
