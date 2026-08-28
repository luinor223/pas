package com.abclogistics.pas.contract.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "customer_counter", schema = "contract")
public class CustomerCounter {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Boolean id = Boolean.TRUE;

    @Column(name = "next_seq", nullable = false)
    private long nextSeq;

    protected CustomerCounter() { } // JPA

    public long allocate() {
        return nextSeq++;
    }

    public Boolean getId() { return id; }
    public long getNextSeq() { return nextSeq; }
}
