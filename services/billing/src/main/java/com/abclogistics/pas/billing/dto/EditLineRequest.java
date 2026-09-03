package com.abclogistics.pas.billing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record EditLineRequest(
    @Min(1) int lineNo,
    @DecimalMin(value = "0.0", inclusive = true) BigDecimal unitPrice,
    @DecimalMin(value = "0.0", inclusive = false) BigDecimal quantity,
    String note,
    // seq-06 m18 version guard: the caller edits against a known version, stale writes lose loudly
    @NotNull Integer version
) {}
