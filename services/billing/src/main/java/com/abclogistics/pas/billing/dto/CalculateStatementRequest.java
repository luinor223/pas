package com.abclogistics.pas.billing.dto;

import jakarta.validation.constraints.*;

public record CalculateStatementRequest(
    @NotNull String contractId,
    @NotBlank String periodCode
) {}
