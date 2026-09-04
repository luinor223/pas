package com.abclogistics.pas.operations.dto;

import com.abclogistics.pas.operations.domain.PeriodCode;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateVolumeRequest(
        @NotNull UUID contractId,
        @NotBlank @Pattern(regexp = PeriodCode.REGEX, message = PeriodCode.MESSAGE)
        String periodCode,
        @NotBlank String serviceCode,
        @NotNull
        @DecimalMin(value = "0.0", message = "quantity must be >= 0")
        @Digits(integer = 15, fraction = 3, message = "quantity must have at most 3 decimal places")
        BigDecimal quantity,
        String note
) {}
