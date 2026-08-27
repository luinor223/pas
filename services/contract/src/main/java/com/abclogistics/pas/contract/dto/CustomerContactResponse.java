package com.abclogistics.pas.contract.dto;

import com.abclogistics.pas.contract.domain.CustomerContact;

import java.util.UUID;

public record CustomerContactResponse(
        UUID id,
        String fullName,
        String title,
        String email,
        String phone,
        boolean primary
) {
    public static CustomerContactResponse of(CustomerContact c) {
        return new CustomerContactResponse(
                c.getId(), c.getFullName(), c.getTitle(), c.getEmail(), c.getPhone(), c.isPrimary());
    }
}
