package com.abclogistics.pas.billing.dto;

import java.math.BigDecimal;
import java.util.List;

public record StatementLineResponse(
    Long id,
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
