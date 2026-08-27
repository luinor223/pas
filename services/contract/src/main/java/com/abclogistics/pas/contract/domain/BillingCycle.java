package com.abclogistics.pas.contract.domain;

/**
 * How often a contract is invoiced. Matched to the {@code billing_cycle} CHECK constraint, which
 * today allows MONTHLY only (4.2 states monthly settlement) — the enum exists so an unsupported
 * cycle is refused with a 422 naming the allowed values, rather than reaching Postgres and coming
 * back as a constraint violation.
 */
public enum BillingCycle {
    MONTHLY
}
