package com.abclogistics.pas.workflow.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateDocumentTypeRequest(
        @NotBlank String name,
        boolean esignEnabled,
        String esignProvider
) {}
