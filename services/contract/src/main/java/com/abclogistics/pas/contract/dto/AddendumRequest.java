package com.abclogistics.pas.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Create/update payload. Which field is required depends on changeType, so the service validates it. */
public record AddendumRequest(
        @NotNull UUID contractId,
        @NotNull String changeType,
        String description,
        @NotNull LocalDate effectiveFrom,
        LocalDate newValidTo,
        String paymentTermOverride,
        @Valid List<ServiceLine> services,
        Integer version) {

    @Schema(name = "AddendumRequestServiceLine")
    public record ServiceLine(
            UUID serviceItemId,
            @NotBlank String serviceCode,
            @NotBlank String serviceName,
            String unit,
            String scopeNote) { }
}
