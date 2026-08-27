package com.abclogistics.pas.workflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record UpdateStepsRequest(
        @NotEmpty @Valid List<StepRequest> steps
) {
    public record StepRequest(
            @NotBlank String name,
            @NotBlank String approverRole,
            @Min(1) int slaHours
    ) {}
}
