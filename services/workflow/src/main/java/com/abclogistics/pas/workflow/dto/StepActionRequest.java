package com.abclogistics.pas.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record StepActionRequest(
        @NotBlank @Pattern(regexp = "APPROVE|REJECT|REQUEST_REVISION", message = "action must be APPROVE, REJECT or REQUEST_REVISION")
        String action,
        String comment
) {}
