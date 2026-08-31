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

    /** APPROVED versions whose validity has begun (scheduler: APPROVED → EFFECTIVE, §9). */
    @Query("""
            select v.id from PriceListVersion v
            where v.status = com.abclogistics.pas.pricing.domain.PriceListVersionStatus.APPROVED
              and v.validFrom <= :today""")
    List<UUID> dueForActivation(@Param("today") LocalDate today);

    /** EFFECTIVE versions past their validity (scheduler: EFFECTIVE → EXPIRED, §9). */
    @Query("""
            select v.id from PriceListVersion v
            where v.status = com.abclogistics.pas.pricing.domain.PriceListVersionStatus.EFFECTIVE
              and v.validTo < :today""")
    List<UUID> dueForExpiry(@Param("today") LocalDate today);

    /** EFFECTIVE versions expiring within the horizon that have not been warned yet (D9), projected
     *  with their price_list_no so the scheduler needs no per-row entity reload. */
    @Query("""
            select new com.abclogistics.pas.pricing.dto.ExpiryWarningRow(
                v.id, v.versionNo, v.validTo, v.createdBy, pl.priceListNo)
            from PriceListVersion v, PriceList pl
            where pl.id = v.priceListId
              and v.status = com.abclogistics.pas.pricing.domain.PriceListVersionStatus.EFFECTIVE
              and v.expiryWarnedAt is null
              and v.validTo between :today and :horizon""")
    List<com.abclogistics.pas.pricing.dto.ExpiryWarningRow> dueForExpiryWarning(
            @Param("today") LocalDate today, @Param("horizon") LocalDate horizon);

    /** EFFECTIVE peers of the same scope that start before the activating version (PRC-04 supersede). */
    @Query("""
            select v from PriceListVersion v
            where v.scopeKey = :scopeKey
              and v.id <> :selfId
              and v.status = com.abclogistics.pas.pricing.domain.PriceListVersionStatus.EFFECTIVE
              and v.validFrom < :validFrom""")
    List<PriceListVersion> effectivePredecessors(@Param("scopeKey") String scopeKey,
                                                 @Param("selfId") UUID selfId,
                                                 @Param("validFrom") LocalDate validFrom);

    /** The version effective at a date for one scope (historical: includes SUPERSEDED/EXPIRED). The
     *  latest valid_from whose range holds the date; caller passes {@code Limit.of(1)}. */
    @Query("""
            select v from PriceListVersion v
            where v.scopeKey = :scopeKey
              and v.status in (:statuses)
              and v.validFrom <= :date and v.validTo >= :date
            order by v.validFrom desc""")
    List<PriceListVersion> effectiveAt(@Param("scopeKey") String scopeKey,
                                       @Param("statuses") List<PriceListVersionStatus> statuses,
                                       @Param("date") LocalDate date,
                                       org.springframework.data.domain.Limit limit);
}
