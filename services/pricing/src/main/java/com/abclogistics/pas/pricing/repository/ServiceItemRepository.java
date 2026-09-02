package com.abclogistics.pas.pricing.repository;

import com.abclogistics.pas.pricing.domain.ServiceItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceItemRepository extends JpaRepository<ServiceItem, UUID> {
    Optional<ServiceItem> findByCode(String code);

    List<ServiceItem> findAllByActiveTrueOrderByCode();

    List<ServiceItem> findAllByOrderByCode();
}
