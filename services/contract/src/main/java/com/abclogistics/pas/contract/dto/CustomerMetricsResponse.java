package com.abclogistics.pas.contract.dto;

import java.util.List;

/** Complete customer-level contract aggregates; never derived from a paged contract response. */
public record CustomerMetricsResponse(
        long activeContracts,
        List<CurrencyValue> approvedContractValues
) {
    /** Decimal string avoids precision loss in JSON consumers such as JavaScript. */
    public record CurrencyValue(String currency, String value) { }
}
