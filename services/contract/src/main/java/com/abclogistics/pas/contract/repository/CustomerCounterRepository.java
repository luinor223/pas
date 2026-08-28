package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.CustomerCounter;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerCounterRepository extends JpaRepository<CustomerCounter, Boolean> {
}
