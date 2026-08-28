package com.abclogistics.pas.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

public final class SecurityUtils {

    private SecurityUtils() { }

    public static Optional<AuthenticatedUser> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    /**
     * Permission check for a rule a method-level {@code @PreAuthorize} cannot express — one that
     * depends on the entity's state, such as CTR-06's "cancelling an ACTIVE contract needs
     * {@code contract:cancel_active}, cancelling a DRAFT does not". Authorities are permissions,
     * never roles.
     */
    public static boolean hasPermission(String permission) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(granted -> permission.equals(granted.getAuthority()));
    }

    public static UUID currentUserId() {
        return currentUser().map(AuthenticatedUser::userId).orElse(null);
    }
}
