package com.abclogistics.pas.billing.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record VolumeLinkResponse(
    UUID id,
    String volumeRecordId,
    String recordNo,
    BigDecimal quantity
) {}
