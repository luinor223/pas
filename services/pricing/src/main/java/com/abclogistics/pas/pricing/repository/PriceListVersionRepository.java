package com.abclogistics.pas.pricing.repository;

import com.abclogistics.pas.pricing.domain.PriceListVersion;
import com.abclogistics.pas.pricing.domain.PriceListVersionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PriceListVersionRepository extends JpaRepository<PriceListVersion, UUID> {

    List<PriceListVersion> findByPriceListIdOrderByVersionNo(UUID priceListId);

    @Query("select coalesce(max(v.versionNo), 0) from PriceListVersion v where v.priceListId = :priceListId")
    int maxVersionNo(@Param("priceListId") UUID priceListId);

    /** Overlap pre-check (PRC-03, app-level): APPROVED/EFFECTIVE peers of the same scope whose
     *  range touches [from,to], excluding the candidate itself. */
    @Query("""
            select v from PriceListVersion v
            where v.scopeKey = :scopeKey
              and v.id <> :selfId
              and v.status in (com.abclogistics.pas.pricing.domain.PriceListVersionStatus.APPROVED,
                               com.abclogistics.pas.pricing.domain.PriceListVersionStatus.EFFECTIVE)
              and v.validFrom <= :to and v.validTo >= :from""")
    List<PriceListVersion> overlapping(@Param("scopeKey") String scopeKey,
                                       @Param("selfId") UUID selfId,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);

    /** Effective-at-date candidates for one scope (historical: includes SUPERSEDED/EXPIRED). */
    @Query("""
            select v from PriceListVersion v
            where v.scopeKey = :scopeKey
              and v.status in (:statuses)
              and v.validFrom <= :date and v.validTo >= :date
            order by v.validFrom desc""")
    List<PriceListVersion> effectiveAt(@Param("scopeKey") String scopeKey,
                                       @Param("statuses") List<PriceListVersionStatus> statuses,
                                       @Param("date") LocalDate date);
}
