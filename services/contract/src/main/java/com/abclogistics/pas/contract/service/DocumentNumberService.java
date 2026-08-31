package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.contract.domain.EntityType;
import com.abclogistics.pas.contract.repository.CounterAllocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Business-key generation (registry §2). Server-generated, never client-supplied. */
@Service
public class DocumentNumberService {

    private static final String CUSTOMER_PREFIX = "CUS";
    private final CounterAllocationRepository counters;

    public DocumentNumberService(CounterAllocationRepository counters) {
        this.counters = counters;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextDocumentNo(EntityType docType, int year) {
        long sequence = counters.allocateDocument(docType, year);
        return "%s-%d-%04d".formatted(prefixFor(docType), year, sequence);
    }

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
