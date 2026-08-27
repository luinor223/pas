package com.abclogistics.pas.contract.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Single-row sequence behind {@code CUS-{seq}}.
 *
 * <p>Separate from {@link DocumentCounter} on purpose: registry §2 gives customer codes NO year
 * segment, so the sequence must run unbroken across years. Keying it by
 * {@code (doc_type, year)} would restart it every January and collide on
 * {@code customer.code}'s UNIQUE.
 *
 * <p>The {@code id boolean primary key default true check (id)} column is the one-row guard:
 * a second row is impossible.
 */
@Entity
@Table(name = "customer_counter", schema = "contract")
public class CustomerCounter {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Boolean id = Boolean.TRUE;

    @Column(name = "next_seq", nullable = false)
    private long nextSeq;

    protected CustomerCounter() { } // JPA

    /** Returns the sequence to use and advances the counter. Caller must hold the row lock. */
    public long allocate() {
        return nextSeq++;
    }

    public Boolean getId() { return id; }
    public long getNextSeq() { return nextSeq; }
}
