package com.abclogistics.pas.operations.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CreateVolumeRequest(
    @NotNull Long contractId,
    @NotBlank String contractCode,
    String contractName,
    Long partnerId,
    String partnerName,
    Long serviceItemId,
    @NotBlank String serviceCode,
    String serviceName,
    @NotNull @DecimalMin("0.01") BigDecimal quantity,
    String unit,
    @NotNull @DecimalMin("0") BigDecimal unitPrice,
    String note
) {}
