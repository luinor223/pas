package com.abclogistics.pas.contract.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Create/update payload for a customer. {@code code} is deliberately absent: it is
 * server-generated ({@code CUS-{seq}}, registry §2) and a client-supplied one would break the
 * sequence's meaning.
 */
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
