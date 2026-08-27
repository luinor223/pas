package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.Contract;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContractRepository extends JpaRepository<Contract, UUID> {

    Optional<Contract> findByContractNo(String contractNo);

    @Query("""
            select c from Contract c
            where (:customerId is null or c.customer.id = :customerId)
              and (:status is null or c.status = :status)
            """)
    Page<Contract> search(@Param("customerId") UUID customerId,
                          @Param("status") DocumentStatus status,
                          Pageable pageable);

    /** D14d sweep: APPROVED contracts whose effective date has arrived (CTR-05). */
    List<Contract> findByStatusAndValidFromLessThanEqual(DocumentStatus status, LocalDate onOrBefore);

    /** D14d sweep: ACTIVE contracts past their end date. */
    List<Contract> findByStatusAndValidToLessThan(DocumentStatus status, LocalDate before);

    /** D9 expiry warning: ACTIVE contracts ending inside the warning window. */
    List<Contract> findByStatusAndValidToBetween(DocumentStatus status, LocalDate from, LocalDate to);
}
