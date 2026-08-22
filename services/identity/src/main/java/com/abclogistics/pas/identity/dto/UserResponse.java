package com.abclogistics.pas.identity.dto;

import com.abclogistics.pas.identity.domain.AppUser;
import com.abclogistics.pas.identity.domain.Role;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String username,
        String email,
        String fullName,
        String department,
        String status,
        List<String> roles,
        Instant lastLoginAt
) {
    public static UserResponse from(AppUser user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.getDepartment().getCode(),
                user.getStatus().name(),
                user.getRoles().stream().map(Role::getCode).sorted().toList(),
                user.getLastLoginAt());
    }
}
