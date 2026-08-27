package com.abclogistics.pas.contract.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Create/update payload. {@code contractNo} is absent by design — server-generated
 * ({@code CTR-{YYYY}-{seq}}, registry §2).
 *
 * <p>{@code vatRate} and {@code paymentTerm} are optional HERE and required at submit
 * (CTR-02): a DRAFT must be saveable while still incomplete. The range check applies whenever a
 * value IS supplied — a null stays null and is never quietly turned into zero.
 *
 * <p>{@code version} is the CTR-01 optimistic lock and is required on update.
 */
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
