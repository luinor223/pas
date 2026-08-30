package com.abclogistics.pas.operations.repository;

import com.abclogistics.pas.operations.domain.OperationPeriod;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OperationPeriodRepository extends JpaRepository<OperationPeriod, UUID> {
    Optional<OperationPeriod> findByPeriodCode(String periodCode);
    boolean existsByPeriodCode(String periodCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from OperationPeriod p where p.periodCode = :code")
    Optional<OperationPeriod> findByPeriodCodeForUpdate(@Param("code") String code);
}
