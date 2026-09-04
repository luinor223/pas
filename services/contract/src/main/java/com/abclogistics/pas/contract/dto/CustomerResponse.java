package com.abclogistics.pas.contract.dto;

import com.abclogistics.pas.contract.domain.Customer;
import com.abclogistics.pas.contract.domain.CustomerContact;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CustomerResponse(
        UUID id,
        String code,
        String name,
        String shortName,
        String taxCode,
        String address,
        String representativeName,
        String representativePosition,
        String segment,
        String status,
        List<CustomerContactResponse> contacts,
        CustomerContactResponse primaryContact,
        Long contractsCount,
        Instant createdAt,
        String createdByName,
        Instant updatedAt
) {
    public static CustomerResponse of(Customer c, List<CustomerContact> contacts, Long contractsCount) {
        List<CustomerContactResponse> mapped = contacts.stream().map(CustomerContactResponse::of).toList();
        CustomerContactResponse primary = contacts.stream()
                .filter(CustomerContact::isPrimary)
                .findFirst()
                .map(CustomerContactResponse::of)
                .orElse(null);
        return new CustomerResponse(
                c.getId(), c.getCode(), c.getName(), c.getShortName(), c.getTaxCode(),
                c.getAddress(), c.getRepresentativeName(), c.getRepresentativePosition(),
                c.getSegment(), c.getStatus().name(),
                mapped,
                primary,
                contractsCount,
                c.getCreatedAt(), c.getCreatedByName(), c.getUpdatedAt());
    }

    public static CustomerResponse ofList(Customer c, CustomerContact primary, Long contractsCount) {
        return new CustomerResponse(
                c.getId(), c.getCode(), c.getName(), c.getShortName(), c.getTaxCode(),
                c.getAddress(), c.getRepresentativeName(), c.getRepresentativePosition(),
                c.getSegment(), c.getStatus().name(),
                List.of(),
                primary == null ? null : CustomerContactResponse.of(primary),
                contractsCount,
                c.getCreatedAt(), c.getCreatedByName(), c.getUpdatedAt());
    }
}
