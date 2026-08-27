package com.abclogistics.pas.contract.domain;

/** registry §9 — what drove a status transition. Persisted on every status_history row (D17). */
public enum TriggerKind {
    /** User action. */
    U,
    /** workflow.completed / workflow.instance_started outcome (D3). */
    W,
    /** esign.session_completed (D10) — not consumed by contract-service (D14e), here for enum parity. */
    E,
    /** Scheduler (D14d). */
    S
}
