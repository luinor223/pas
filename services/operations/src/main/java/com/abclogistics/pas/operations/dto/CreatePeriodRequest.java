package com.abclogistics.pas.operations.dto;

import jakarta.validation.constraints.*;

public record CreatePeriodRequest(
    @NotBlank(message = "Period code is required") @Size(max = 50) String periodCode,
    @NotBlank(message = "Period name is required") @Size(max = 100) String periodName,
    @Min(1) @Max(12) int month,
    @Min(2020) @Max(2030) int year,
    @NotNull String startDate,
    @NotNull String endDate
) {}
