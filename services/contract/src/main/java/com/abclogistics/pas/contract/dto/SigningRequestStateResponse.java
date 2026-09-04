package com.abclogistics.pas.contract.dto;

import com.abclogistics.pas.contract.service.SigningRequestService;

import java.util.UUID;

public record SigningRequestStateResponse(
        boolean canSendForSigning,
        boolean requestQueued,
        UUID sessionId
) {
    public static SigningRequestStateResponse of(SigningRequestService.State state) {
        return new SigningRequestStateResponse(
                state.canSendForSigning(), state.requestQueued(), state.sessionId());
    }
}
