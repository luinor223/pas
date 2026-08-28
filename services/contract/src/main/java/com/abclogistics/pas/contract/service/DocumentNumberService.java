package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.repository.CounterAllocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business-key generation (registry §2). Numbers are server-generated and never client-supplied.
 *
 * <p>Allocation is a single {@code INSERT … ON CONFLICT DO UPDATE … RETURNING} statement rather
 * than a select-for-update followed by an insert. The two-step version has a hole the row lock
 * cannot close: on the first allocation for a {@code (doc_type, year)} pair there is no row to
 * lock, so concurrent callers all find nothing and all insert, and every one but the first dies on
 * the primary key. The upsert has no such window — Postgres serialises the conflicting writers on
 * the index entry itself, and each caller gets a distinct sequence value back.
 */
@Service
public class DocumentNumberService {

    private static final String CUSTOMER_PREFIX = "CUS";
    private final CounterAllocationRepository counters;

    public DocumentNumberService(CounterAllocationRepository counters) {
        this.counters = counters;
    }

    /**
     * {@code CTR-{YYYY}-{seq}} or {@code ADD-{YYYY}-{seq}}, per type per year.
     *
     * <p>MANDATORY propagation: the allocation must commit or roll back with the row that carries
     * the number. In its own transaction it would burn a sequence value whenever the caller later
     * fails, leaving gaps in a business key people read.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextDocumentNo(EntityType docType, int year) {
        long sequence = counters.allocateDocument(docType, year);
        return "%s-%d-%04d".formatted(prefixFor(docType), year, sequence);
    }

    /**
     * {@code CUS-{seq}} — no year segment, so the sequence runs unbroken across years.
     *
     * <p>A year-keyed counter would hand out {@code CUS-0001} again every January and collide on
     * {@code customer.code}'s UNIQUE, which is why this counter is a separate single-row table.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextCustomerCode() {
        long sequence = counters.allocateCustomer();
        return "%s-%04d".formatted(CUSTOMER_PREFIX, sequence);
    }

    private static String prefixFor(EntityType docType) {
        return switch (docType) {
            case CONTRACT -> "CTR";
            case ADDENDUM -> "ADD";
        };
    }
}
