package com.abclogistics.pas.contract.dto;

import com.abclogistics.pas.contract.service.DocumentCancellationService.Outcome;

/**
 * {@code CANCELLED} (200) or {@code PENDING} (202). A bare 202 would leave the client guessing;
 * the detail says why the cancellation has not resolved and that retrying the same call is the
 * correct response — the M2 handoff is deliberately not a single round trip.
 */
public record CancelResponse(String status, String detail) {

    private static final String PENDING_DETAIL =
            "A workflow dispatch is still in flight; the contract keeps its current status. Retry this call.";

    public static CancelResponse of(Outcome outcome) {
        return outcome == Outcome.CANCELLED
                ? new CancelResponse(Outcome.CANCELLED.name(), null)
                : new CancelResponse(Outcome.PENDING.name(), PENDING_DETAIL);
    }
}
