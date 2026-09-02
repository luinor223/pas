package com.abclogistics.pas.billing.dto;

import java.math.BigDecimal;

public record EditLineRequest(
    int lineNo,
    BigDecimal unitPrice,
    BigDecimal quantity,
    String note
) {}
