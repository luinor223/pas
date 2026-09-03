package com.abclogistics.pas.billing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record AddLineRequest(
    @NotBlank String serviceCode,
    String serviceName,
    String unit,
    @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal unitPrice,
    @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal quantity,
    String note
) {}
