package com.abclogistics.pas.billing.dto;

import java.util.List;

public record AdjustmentRequest(
    String reason,
    List<AdjustmentLineInput> lines
) {
    public record AdjustmentLineInput(
        String serviceCode,
        String serviceName,
        String unit,
        java.math.BigDecimal unitPrice,
        java.math.BigDecimal quantity,
        String note
    ) {}
}
