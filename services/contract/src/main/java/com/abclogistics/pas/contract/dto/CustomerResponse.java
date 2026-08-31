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
        Instant createdAt,
        String createdByName,
        Instant updatedAt
) {
    public static CustomerResponse of(Customer c, List<CustomerContact> contacts) {
        return new CustomerResponse(
                c.getId(), c.getCode(), c.getName(), c.getShortName(), c.getTaxCode(),
                c.getAddress(), c.getRepresentativeName(), c.getRepresentativePosition(),
                c.getSegment(), c.getStatus().name(),
                contacts.stream().map(CustomerContactResponse::of).toList(),
                c.getCreatedAt(), c.getCreatedByName(), c.getUpdatedAt());
    }
}
