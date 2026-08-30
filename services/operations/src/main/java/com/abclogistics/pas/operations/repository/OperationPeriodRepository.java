package com.abclogistics.pas.operations.repository;

import com.abclogistics.pas.operations.domain.OperationPeriod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OperationPeriodRepository extends JpaRepository<OperationPeriod, UUID> {
    Optional<OperationPeriod> findByPeriodCode(String periodCode);
    boolean existsByPeriodCode(String periodCode);
}
