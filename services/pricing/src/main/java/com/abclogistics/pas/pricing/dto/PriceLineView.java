package com.abclogistics.pas.pricing.dto;

import java.math.BigDecimal;

/** A price line joined with its catalog item, for display. */
public record PriceLineView(String serviceCode, String serviceName, String unit, BigDecimal unitPrice) {}
