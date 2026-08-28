package com.abclogistics.pas.contract.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Create/update payload. {@code addendumNo} is absent by design — server-generated
 * ({@code ADD-{YYYY}-{seq}}, registry §2).
 *
 * <p>Which of {@code newValidTo} / {@code paymentTermOverride} is required depends on
 * {@code changeType}, so neither can be annotated here; {@code AddendumService} validates the
 * pairing. Carries no price data at all (D8).
 *
 * <p>{@code version} is the CTR-01 optimistic lock and is required on update.
 */
public record AddendumRequest(
        @NotNull UUID contractId,
        @NotNull String changeType,
        String description,
        @NotNull LocalDate effectiveFrom,
        LocalDate newValidTo,
        String paymentTermOverride,
        List<ServiceLine> services,
        Integer version) {

    /** Record/display only — never an input to scope enforcement. */
    public record ServiceLine(
            UUID serviceItemId,
            @NotNull String serviceCode,
            @NotNull String serviceName,
            String unit,
            String scopeNote) { }
}
