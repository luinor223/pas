package com.abclogistics.pas.operations.dto;

import java.math.BigDecimal;

public record UpdateVolumeRequest(
    BigDecimal quantity,
    String unit,
    BigDecimal unitPrice,
    String note
) {}
