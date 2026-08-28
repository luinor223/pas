package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.EntityType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL-specific, atomic allocation of customer and document counter values.
 *
 * <p>The allocation cannot be expressed safely as a JPA read followed by a write: on the first
 * allocation there is no row for {@code SELECT FOR UPDATE} to lock. A single
 * {@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING} lets PostgreSQL serialize competing
 * writers on the unique index entry and return a different value to every caller.
 *
 * <p>Transaction ownership deliberately remains in {@code DocumentNumberService}; this class is
 * only the persistence mechanism and always joins the caller's transaction.
 */
@Repository
public class CounterAllocationRepository {

    private static final String ALLOCATE_DOCUMENT = """
            insert into contract.document_counter (doc_type, year, next_seq)
            values (:docType, :year, 2)
            on conflict (doc_type, year)
            do update set next_seq = contract.document_counter.next_seq + 1
            returning next_seq - 1
            """;

    private static final String ALLOCATE_CUSTOMER = """
            insert into contract.customer_counter (id, next_seq)
            values (true, 2)
            on conflict (id)
            do update set next_seq = contract.customer_counter.next_seq + 1
            returning next_seq - 1
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /** Returns the next value for one document type and year. */
    public long allocateDocument(EntityType documentType, int year) {
        Number sequence = (Number) entityManager.createNativeQuery(ALLOCATE_DOCUMENT)
                .setParameter("docType", documentType.name())
                .setParameter("year", year)
                .getSingleResult();
        return sequence.longValue();
    }

    /** Returns the next value from the single customer counter, which does not reset each year. */
    public long allocateCustomer() {
        Number sequence = (Number) entityManager.createNativeQuery(ALLOCATE_CUSTOMER)
                .getSingleResult();
        return sequence.longValue();
    }
}
