package com.abclogistics.pas.pricing.dto;

import com.abclogistics.pas.pricing.domain.ServiceItem;

public record ServiceItemResponse(String code, String name, String unit, boolean active) {
    public static ServiceItemResponse of(ServiceItem item) {
        return new ServiceItemResponse(item.getCode(), item.getName(), item.getUnit(), item.isActive());
    }
}
