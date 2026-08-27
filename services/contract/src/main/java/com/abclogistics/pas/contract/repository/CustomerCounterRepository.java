package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.CustomerCounter;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface CustomerCounterRepository extends JpaRepository<CustomerCounter, Boolean> {

    /** Row-locking read of the single row; same rationale as {@code DocumentCounterRepository.lockFor}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CustomerCounter c where c.id = true")
    Optional<CustomerCounter> lockSingleton();
}
