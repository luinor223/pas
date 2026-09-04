package com.abclogistics.pas.operations.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record UpdateVolumeRequest(
        @NotNull
        @DecimalMin(value = "0.0", message = "quantity must be >= 0")
        @Digits(integer = 15, fraction = 3, message = "quantity must have at most 3 decimal places")
        BigDecimal quantity,
        String note
) {}
