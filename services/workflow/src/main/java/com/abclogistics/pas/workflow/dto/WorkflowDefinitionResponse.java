package com.abclogistics.pas.workflow.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowDefinitionResponse(
        UUID id,
        String documentTypeCode,
        String documentTypeName,
        int versionNo,
        String name,
        boolean active,
        Instant createdAt,
        UUID createdBy,
        List<StepDefinitionDto> steps
) {
    public record StepDefinitionDto(
            UUID id,
            int stepOrder,
            String name,
            String approverRole,
            int slaHours
    ) {}
}
