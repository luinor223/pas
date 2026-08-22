package com.abclogistics.pas.identity.dto;

import java.time.Instant;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        Instant expiresAt
) {
}
