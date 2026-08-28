package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.Addendum;
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

public interface AddendumRepository extends JpaRepository<Addendum, UUID> {

    Optional<Addendum> findByAddendumNo(String addendumNo);

    @Query("""
            select a from Addendum a
            where (:contractId is null or a.contract.id = :contractId)
              and (:status is null or a.status = :status)
            """)
    Page<Addendum> search(@Param("contractId") UUID contractId,
                          @Param("status") DocumentStatus status,
                          Pageable pageable);

    List<Addendum> findByContractId(UUID contractId);

    List<Addendum> findByStatusAndEffectiveFromLessThanEqual(DocumentStatus status, LocalDate onOrBefore);
}
