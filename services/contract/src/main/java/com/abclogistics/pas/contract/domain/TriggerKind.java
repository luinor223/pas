package com.abclogistics.pas.contract.domain;

/** registry §9 — what drove a status transition. Persisted on every status_history row. */
public enum TriggerKind {
    U,
    W,
    E,
    S
}
