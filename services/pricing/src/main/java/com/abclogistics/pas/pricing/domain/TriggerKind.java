package com.abclogistics.pas.pricing.domain;

/** What drove a status change: U user action, S system/scheduler, W workflow outcome (§9, D17). */
public enum TriggerKind {
    U, S, W
}
