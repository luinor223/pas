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

    /**
     * D14d activation sweep. The order is the point, not a convenience: two addenda against one
     * contract must apply oldest-effective-date first, or a sweep catching up on a backlog can
     * apply a newer PAYMENT_TERMS before an older one and leave the older value standing.
     * created_at breaks a same-effective_from tie, so the addendum created later applies later
     * and its value stands — the rule 4.3 leaves open. id is only a last resort for two rows
     * created at the identical timestamp: it makes the order stable, not meaningful.
     */
    @Query("""
            select a from Addendum a
            where a.status = :status and a.effectiveFrom <= :onOrBefore
            order by a.effectiveFrom asc, a.createdAt asc, a.id asc
            """)
    List<Addendum> dueForActivation(@Param("status") DocumentStatus status,
                                    @Param("onOrBefore") LocalDate onOrBefore);
}
