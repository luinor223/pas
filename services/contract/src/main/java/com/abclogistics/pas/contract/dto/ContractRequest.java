package com.abclogistics.pas.contract.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ContractRequest(
        @NotNull UUID customerId,
        String description,
        @NotNull String serviceGroup,
        BigDecimal value,
        String currency,
        @NotNull LocalDate validFrom,
        @NotNull LocalDate validTo,
        String paymentTerm,
        String billingCycle,
        @DecimalMin(value = "0", message = "vatRate must be between 0 and 100")
        @DecimalMax(value = "100", message = "vatRate must be between 0 and 100")
        BigDecimal vatRate,
        String penaltyTerms,
        String serviceClause,
        Integer version
) { }
