package com.abclogistics.pas.common.security;

import java.util.List;
import java.util.UUID;

/** The principal placed in the security context from the edge-injected identity headers. */
public record AuthenticatedUser(
        UUID userId,
        String username,
        String fullName,
        String department,
        List<String> roles
) {
}
