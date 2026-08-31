package com.abclogistics.pas.contract.dto;

import com.abclogistics.pas.contract.service.DocumentCancellationService.Outcome;

public record CancelResponse(String status, String detail) {

    private static final String PENDING_DETAIL =
            "A workflow dispatch is still in flight; the contract keeps its current status. Retry this call.";

    public static CancelResponse of(Outcome outcome) {
        return outcome == Outcome.CANCELLED
                ? new CancelResponse(Outcome.CANCELLED.name(), null)
                : new CancelResponse(Outcome.PENDING.name(), PENDING_DETAIL);
    }
}
