package com.abclogistics.pas.billing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record AdjustmentRequest(
    String reason,
    @NotEmpty @Valid List<AdjustmentLineInput> lines
) {
    public record AdjustmentLineInput(
        @NotBlank String serviceCode,
        String serviceName,
        String unit,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) java.math.BigDecimal unitPrice,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) java.math.BigDecimal quantity,
        String note
    ) {}
}
