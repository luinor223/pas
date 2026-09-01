package com.abclogistics.pas.operations.dto;

import java.math.BigDecimal;

public record VolumeResponse(
    Long id,
    String periodCode,
    Long contractId,
    String contractCode,
    String contractName,
    Long partnerId,
    String partnerName,
    Long serviceItemId,
    String serviceCode,
    String serviceName,
    BigDecimal quantity,
    String unit,
    BigDecimal unitPrice,
    BigDecimal volumeCostAmount,
    String note
) {}
