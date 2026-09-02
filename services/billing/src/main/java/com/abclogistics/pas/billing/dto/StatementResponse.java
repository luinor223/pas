package com.abclogistics.pas.billing.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StatementResponse(
    UUID id,
    String statementNo,
    String contractId,
    String contractNo,
    String customerId,
    String customerName,
    String periodCode,
    String periodStart,
    String periodEnd,
    String priceListNo,
    Integer priceListVersionNo,
    String paymentTerm,
    BigDecimal vatRate,
    BigDecimal subtotal,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    String currency,
    String status,
    UUID adjustsStatementId,
    String reconciledAt,
    String issuedAt,
    LocalDate dueDate,
    int version,
    List<StatementLineResponse> lines
) {}
