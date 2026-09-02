package com.abclogistics.pas.operations.dto;

import com.abclogistics.pas.operations.domain.PeriodCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreatePeriodRequest(
        @NotBlank @Pattern(regexp = PeriodCode.REGEX, message = PeriodCode.MESSAGE)
        String periodCode
) {}
