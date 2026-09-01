package com.abclogistics.pas.operations.repository;

import com.abclogistics.pas.operations.domain.OperationPeriod;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OperationPeriodRepository extends JpaRepository<OperationPeriod, Long> {

    Optional<OperationPeriod> findByPeriodCode(String periodCode);

    @Query("SELECT op FROM OperationPeriod op ORDER BY op.year DESC, op.month DESC")
    Page<OperationPeriod> findAllSorted(Pageable pageable);

    boolean existsByPeriodCode(String periodCode);
}
