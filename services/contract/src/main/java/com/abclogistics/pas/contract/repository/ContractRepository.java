package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.Contract;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.ServiceGroup;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContractRepository extends JpaRepository<Contract, UUID> {

    // Response mapping happens after the service transaction closes.
    @Override
    @EntityGraph(attributePaths = "customer")
    Optional<Contract> findById(UUID id);

    @EntityGraph(attributePaths = "customer")
    Optional<Contract> findByContractNo(String contractNo);

    @EntityGraph(attributePaths = "customer")
    @Query("""
            select c from Contract c
            where (:customerId is null or c.customer.id = :customerId)
              and (:status is null or c.status = :status)
              and (:serviceGroup is null or c.serviceGroup = :serviceGroup)
              and (:q is null
                   or lower(c.contractNo) like :q
                   or lower(c.description) like :q
                   or lower(c.customer.name) like :q)
            """)
    Page<Contract> search(@Param("customerId") UUID customerId,
                          @Param("status") DocumentStatus status,
                          @Param("serviceGroup") ServiceGroup serviceGroup,
                          @Param("q") String q,
                          Pageable pageable);

    /**
     * D14d activation sweep. Ordered so a sweep that has not run for days replays the backlog in
     * the order the dates actually fell; created_at then id only break a same-day tie.
     */
    @Query("""
            select c from Contract c
            where c.status = :status and c.validFrom <= :onOrBefore
            order by c.validFrom asc, c.createdAt asc, c.id asc
            """)
    List<Contract> dueForActivation(@Param("status") DocumentStatus status,
                                    @Param("onOrBefore") LocalDate onOrBefore);

    /** D14d expiry sweep. valid_to is inclusive, so a contract expires the day after it. */
    @Query("""
            select c from Contract c
            where c.status = :status and c.validTo < :before
            order by c.validTo asc, c.createdAt asc, c.id asc
            """)
    List<Contract> dueForExpiry(@Param("status") DocumentStatus status,
                                @Param("before") LocalDate before);

    /**
     * D9 warning sweep. The stamp is compared against the CURRENT valid_to: an extension that
     * moved it makes the two differ again, so the new term gets its own warning without anything
     * having to remember to clear the stamp.
     */
    @Query("""
            select c from Contract c
            where c.status = :status
              and c.validTo between :from and :to
              and (c.lastExpiryWarningFor is null or c.lastExpiryWarningFor <> c.validTo)
            order by c.validTo asc, c.createdAt asc, c.id asc
            """)
    List<Contract> dueForExpiryWarning(@Param("status") DocumentStatus status,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);
}
