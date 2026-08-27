package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.contract.domain.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Customer master data (4.1). Customers have no approval workflow — they are activated and
 * suspended directly, so nothing here writes {@code status_history}.
 */
@Service
public class CustomerService {

    @Transactional(readOnly = true)
    public Page<Customer> search(String query, String status, Pageable pageable) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    @Transactional(readOnly = true)
    public Customer get(UUID id) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /** {@code code} is allocated here, never taken from the request. */
    @Transactional
    public Customer create(Customer draft) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    @Transactional
    public Customer update(UUID id, Customer changes) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    /** Suspending does not touch existing contracts; it blocks new submits (CTR-02). */
    @Transactional
    public void suspend(UUID id, String reason) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }

    @Transactional
    public void activate(UUID id) {
        throw new UnsupportedOperationException("session-3 Phase B");
    }
}
