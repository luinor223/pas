package com.abclogistics.pas.workflow.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateDefinitionRequest(
        @NotBlank String documentTypeCode,
        @NotBlank String name
) {}
