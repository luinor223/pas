package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.CustomerCounter;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Read access to the {@code CUS-} counter. Allocation is an atomic upsert in
 * {@code DocumentNumberService}; see the note there.
 */
public interface CustomerCounterRepository extends JpaRepository<CustomerCounter, Boolean> {
}
