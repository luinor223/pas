package com.abclogistics.pas.pricing.repository;

import com.abclogistics.pas.pricing.domain.PriceList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PriceListRepository extends JpaRepository<PriceList, UUID> {

    @Query(value = "select nextval('pricing.price_list_no_seq')", nativeQuery = true)
    long nextPriceListNo();

    @Query("""
            select pl from PriceList pl
            where (:customerId is null or pl.customerId = :customerId)
              and (:contractId is null or pl.contractId = :contractId)
              and (:serviceGroup is null or pl.serviceGroup = :serviceGroup)
            order by pl.priceListNo""")
    List<PriceList> search(@Param("customerId") UUID customerId,
                           @Param("contractId") UUID contractId,
                           @Param("serviceGroup") String serviceGroup);
}
