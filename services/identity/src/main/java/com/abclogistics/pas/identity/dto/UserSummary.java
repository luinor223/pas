package com.abclogistics.pas.identity.dto;

import com.abclogistics.pas.identity.domain.AppUser;
import com.abclogistics.pas.identity.domain.Role;

import java.util.List;
import java.util.UUID;

/** Minimal user profile shared by the login payload and GET /users/me. */
public record UserSummary(
        UUID id,
        String username,
        String fullName,
        String department,
        List<String> roles
) {
    public static UserSummary from(AppUser user) {
        return new UserSummary(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getDepartment().getCode(),
                user.getRoles().stream().map(Role::getCode).toList());
    }
}
