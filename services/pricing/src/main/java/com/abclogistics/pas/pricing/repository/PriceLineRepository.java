package com.abclogistics.pas.pricing.repository;

import com.abclogistics.pas.pricing.domain.PriceLine;
import com.abclogistics.pas.pricing.dto.PriceLineView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface PriceLineRepository extends JpaRepository<PriceLine, UUID> {

    List<PriceLine> findByVersionId(UUID versionId);

    boolean existsByVersionId(UUID versionId);

    /**
     * Bulk delete as immediate SQL (not a per-entity removal deferred to flush), so replaceLines'
     * delete runs before its re-inserts. A derived deleteBy would queue the deletes and Hibernate
     * flushes inserts first, colliding on uq_price_line (version_id, service_item_id). Flush before
     * and clear after keep the persistence context consistent with the direct delete.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PriceLine pl where pl.versionId = :versionId")
    void deleteByVersionId(@Param("versionId") UUID versionId);

    /** Lines joined with their catalog item, for display and the effective-price response. */
    @Query("""
            select new com.abclogistics.pas.pricing.dto.PriceLineView(si.code, si.name, si.unit, l.unitPrice)
            from PriceLine l, ServiceItem si
            where si.id = l.serviceItemId and l.versionId = :versionId
            order by si.code""")
    List<PriceLineView> viewsByVersion(@Param("versionId") UUID versionId);
}
