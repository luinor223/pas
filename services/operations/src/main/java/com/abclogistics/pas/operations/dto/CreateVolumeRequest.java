package com.abclogistics.pas.operations.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateVolumeRequest(
        @NotNull UUID contractId,
        @NotBlank @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$", message = "period_code must be YYYY-MM")
        String periodCode,
        @NotBlank String serviceCode,
        @NotNull @DecimalMin(value = "0.0", message = "quantity must be >= 0")
        BigDecimal quantity,
        String note
) {}
