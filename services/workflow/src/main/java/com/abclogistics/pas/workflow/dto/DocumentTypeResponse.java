package com.abclogistics.pas.workflow.dto;

import java.util.UUID;

public record DocumentTypeResponse(
        UUID id,
        String code,
        String name,
        String numberPrefix,
        boolean esignEnabled,
        String esignProvider
) {}
