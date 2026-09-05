package com.abclogistics.pas.billing.dto;

import java.util.UUID;

public record WorkflowProgressResponse(
    UUID id,
    Object workflowInstance
) {}
