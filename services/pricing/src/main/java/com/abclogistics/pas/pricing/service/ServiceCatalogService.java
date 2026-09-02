package com.abclogistics.pas.pricing.service;

import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.pricing.domain.ServiceItem;
import com.abclogistics.pas.pricing.repository.ServiceItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ServiceCatalogService {

    private final ServiceItemRepository items;

    public ServiceCatalogService(ServiceItemRepository items) {
        this.items = items;
    }

    @Transactional(readOnly = true)
    public List<ServiceItem> list(boolean activeOnly) {
        return activeOnly ? items.findAllByActiveTrueOrderByCode() : items.findAllByOrderByCode();
    }

    @Transactional(readOnly = true)
    public ServiceItem getByCode(String code) {
        return items.findByCode(code)
                .orElseThrow(() -> new NotFoundException("No service item with code " + code));
    }
}
