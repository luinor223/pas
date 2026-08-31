package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.EntityType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

/** Atomic counter allocation: a single INSERT ... ON CONFLICT ... RETURNING, joining the caller's transaction. */
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

    public long allocateDocument(EntityType documentType, int year) {
        Number sequence = (Number) entityManager.createNativeQuery(ALLOCATE_DOCUMENT)
                .setParameter("docType", documentType.name())
                .setParameter("year", year)
                .getSingleResult();
        return sequence.longValue();
    }

    public long allocateCustomer() {
        Number sequence = (Number) entityManager.createNativeQuery(ALLOCATE_CUSTOMER)
                .getSingleResult();
        return sequence.longValue();
    }
}
