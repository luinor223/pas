package com.abclogistics.pas.billing.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record StatementLineResponse(
    UUID id,
    int lineNo,
    String serviceCode,
    String serviceName,
    String unit,
    BigDecimal unitPrice,
    BigDecimal quantity,
    BigDecimal amount,
    String source,
    String note,
    List<VolumeLinkResponse> volumeLinks
) {}
