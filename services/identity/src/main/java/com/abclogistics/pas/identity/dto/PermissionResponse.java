package com.abclogistics.pas.identity.dto;

import com.abclogistics.pas.identity.domain.Permission;

import java.util.UUID;

public record PermissionResponse(UUID id, String code, String description) {
    public static PermissionResponse from(Permission p) {
        return new PermissionResponse(p.getId(), p.getCode(), p.getDescription());
    }
}
