package com.abclogistics.pas.pricing.controller;

import com.abclogistics.pas.pricing.dto.ServiceItemResponse;
import com.abclogistics.pas.pricing.service.ServiceCatalogService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/service-items")
public class ServiceItemController {

    private final ServiceCatalogService catalog;

    public ServiceItemController(ServiceCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('pricelist:read') or hasAuthority('volume:read')")
    public List<ServiceItemResponse> list(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return catalog.list(activeOnly).stream().map(ServiceItemResponse::of).toList();
    }

    @GetMapping("/{code}")
    @PreAuthorize("hasAuthority('pricelist:read') or hasAuthority('volume:read')")
    public ServiceItemResponse get(@PathVariable String code) {
        return ServiceItemResponse.of(catalog.getByCode(code));
    }
}
