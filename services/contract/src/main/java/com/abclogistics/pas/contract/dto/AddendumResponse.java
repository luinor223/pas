package com.abclogistics.pas.contract.dto;

import com.abclogistics.pas.contract.domain.Addendum;
import com.abclogistics.pas.contract.domain.AddendumServiceLine;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AddendumResponse(
        UUID id,
        String addendumNo,
        UUID contractId,
        String contractNo,
        String changeType,
        String description,
        LocalDate effectiveFrom,
        LocalDate newValidTo,
        String paymentTermOverride,
        String status,
        boolean canEdit,
        boolean canSubmit,
        String submitBlockedReason,
        boolean canRevise,
        boolean canCancel,
        List<ServiceLine> services,
        int version) {

    @Schema(name = "AddendumResponseServiceLine")
    public record ServiceLine(UUID id, UUID serviceItemId, String serviceCode,
                              String serviceName, String unit, String scopeNote) { }

    public static AddendumResponse of(Addendum addendum) {
        return of(addendum, false);
    }

    public static AddendumResponse of(Addendum addendum, boolean hasAttachment) {
        DocumentActionCapabilities capabilities =
                DocumentActionCapabilities.forAddendum(addendum, hasAttachment);
        return new AddendumResponse(
                addendum.getId(),
                addendum.getAddendumNo(),
                addendum.getContract().getId(),
                addendum.getContract().getContractNo(),
                addendum.getChangeType().name(),
                addendum.getDescription(),
                addendum.getEffectiveFrom(),
                addendum.getNewValidTo(),
                addendum.getPaymentTermOverride(),
                addendum.getStatus().name(),
                capabilities.canEdit(),
                capabilities.canSubmit(),
                capabilities.submitBlockedReason(),
                capabilities.canRevise(),
                capabilities.canCancel(),
                addendum.getServices().stream().map(AddendumResponse::line).toList(),
                addendum.getVersion());
    }

    private static ServiceLine line(AddendumServiceLine line) {
        return new ServiceLine(line.getId(), line.getServiceItemId(), line.getServiceCode(),
                line.getServiceName(), line.getUnit(), line.getScopeNote());
    }
}
