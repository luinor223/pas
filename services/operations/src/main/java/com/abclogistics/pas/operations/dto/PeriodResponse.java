package com.abclogistics.pas.operations.dto;

public record PeriodResponse(
    Long id,
    String periodCode,
    String periodName,
    int month,
    int year,
    String startDate,
    String endDate,
    String status,
    long volumeCount
) {}
