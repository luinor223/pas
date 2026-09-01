package com.abclogistics.pas.billing.dto;

import java.math.BigDecimal;

public record VolumeLinkResponse(
    Long id,
    String volumeRecordId,
    String recordNo,
    BigDecimal quantity
) {}
