package com.abclogistics.pas.contract.dto;

import com.abclogistics.pas.contract.domain.Addendum;
import com.abclogistics.pas.contract.domain.AddendumServiceLine;

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
        List<ServiceLine> services,
        int version) {

    public record ServiceLine(UUID id, UUID serviceItemId, String serviceCode,
                              String serviceName, String unit, String scopeNote) { }

    public static AddendumResponse of(Addendum addendum) {
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
                addendum.getServices().stream().map(AddendumResponse::line).toList(),
                addendum.getVersion());
    }

    private static ServiceLine line(AddendumServiceLine line) {
        return new ServiceLine(line.getId(), line.getServiceItemId(), line.getServiceCode(),
                line.getServiceName(), line.getUnit(), line.getScopeNote());
    }
}
