package com.abclogistics.pas.contract.dto;

import com.abclogistics.pas.contract.domain.Contract;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ContractResponse(
        UUID id,
        String contractNo,
        UUID customerId,
        String customerName,
        String description,
        String serviceGroup,
        BigDecimal value,
        String currency,
        LocalDate validFrom,
        LocalDate validTo,
        String paymentTerm,
        String billingCycle,
        BigDecimal vatRate,
        String penaltyTerms,
        String serviceClause,
        String status,
        boolean editable,
        int version,
        Instant createdAt,
        String createdByName,
        Instant updatedAt
) {
    public static ContractResponse of(Contract c) {
        return new ContractResponse(
                c.getId(), c.getContractNo(),
                c.getCustomer().getId(), c.getCustomer().getName(),
                c.getDescription(), c.getServiceGroup().name(), c.getValue(), c.getCurrency(),
                c.getValidFrom(), c.getValidTo(), c.getPaymentTerm(), c.getBillingCycle().name(),
                c.getVatRate(), c.getPenaltyTerms(), c.getServiceClause(),
                c.getStatus().name(), c.getStatus().isEditable(), c.getVersion(),
                c.getCreatedAt(), c.getCreatedByName(), c.getUpdatedAt());
    }
}
