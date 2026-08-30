package com.abclogistics.pas.operations.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreatePeriodRequest(
        @NotBlank @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$", message = "period_code must be YYYY-MM")
        String periodCode
) {}
