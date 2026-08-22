package com.abclogistics.pas.identity.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        Instant expiresAt,
        UserSummary user
) {
    public record UserSummary(
            UUID id,
            String username,
            String fullName,
            String department,
            List<String> roles
    ) {
    }
}
