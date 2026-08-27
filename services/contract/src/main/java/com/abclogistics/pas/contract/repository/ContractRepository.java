package com.abclogistics.pas.contract.repository;

import com.abclogistics.pas.contract.domain.Contract;
import com.abclogistics.pas.contract.domain.DocumentStatus;
import com.abclogistics.pas.contract.domain.ServiceGroup;
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

    /**
     * The filter set openapi {@code GET /contracts} documents; every parameter is optional.
     *
     * <p>{@code q} arrives as a ready lower-cased {@code %pattern%}, not as a bare term. Building
     * it in SQL means {@code '%' || ? || '%'}, and Postgres has no type to infer for that
     * parameter when it is null — it resolves the concatenation to bytea and the query dies on
     * {@code lower(bytea) does not exist}.
     */
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

    /** D14d sweep: APPROVED contracts whose effective date has arrived (CTR-05). */
    List<Contract> findByStatusAndValidFromLessThanEqual(DocumentStatus status, LocalDate onOrBefore);

    /** D14d sweep: ACTIVE contracts past their end date. */
    List<Contract> findByStatusAndValidToLessThan(DocumentStatus status, LocalDate before);

    /** D9 expiry warning: ACTIVE contracts ending inside the warning window. */
    List<Contract> findByStatusAndValidToBetween(DocumentStatus status, LocalDate from, LocalDate to);
}
