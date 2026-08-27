package com.abclogistics.pas.contract.domain;

/** Polymorphic owner discriminator for attachment and status_history (db-contract.md). */
public enum EntityType {
    CONTRACT, ADDENDUM
}
