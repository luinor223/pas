package com.abclogistics.pas.identity.dto;

import java.time.Instant;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        Instant expiresAt,
        UserSummary user
) {
}
