package com.abclogistics.pas.contract.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CustomerContactRequest(
        @NotBlank String fullName,
        String title,
        @Email String email,
        String phone,
        boolean primary
) { }
