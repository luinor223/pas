package com.abclogistics.pas.contract.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CustomerRequest(
        @NotBlank String name,
        String shortName,
        String taxCode,
        String address,
        String representativeName,
        String representativePosition,
        String segment,
        @Valid List<CustomerContactRequest> contacts
) { }
