package com.abclogistics.pas.operations.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdateVolumeRequest(
        @NotNull @DecimalMin(value = "0.0", message = "quantity must be >= 0")
        BigDecimal quantity,
        String note
) {}
