package com.abclogistics.pas.pricing.repository;

import com.abclogistics.pas.pricing.domain.PriceLine;
import com.abclogistics.pas.pricing.dto.PriceLineView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface PriceLineRepository extends JpaRepository<PriceLine, UUID> {

    List<PriceLine> findByVersionId(UUID versionId);

    @Transactional
    void deleteByVersionId(UUID versionId);

    /** Lines joined with their catalog item, for display and the effective-price response. */
    @Query("""
            select new com.abclogistics.pas.pricing.dto.PriceLineView(si.code, si.name, si.unit, l.unitPrice)
            from PriceLine l, ServiceItem si
            where si.id = l.serviceItemId and l.versionId = :versionId
            order by si.code""")
    List<PriceLineView> viewsByVersion(@Param("versionId") UUID versionId);
}
