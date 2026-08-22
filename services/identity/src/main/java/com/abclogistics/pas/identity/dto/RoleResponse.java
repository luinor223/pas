package com.abclogistics.pas.identity.dto;

import com.abclogistics.pas.identity.domain.Permission;
import com.abclogistics.pas.identity.domain.Role;

import java.util.List;

public record RoleResponse(
        String code,
        String name,
        List<String> permissions
) {
    public static RoleResponse from(Role role) {
        return new RoleResponse(
                role.getCode(),
                role.getName(),
                role.getPermissions().stream().map(Permission::getCode).sorted().toList());
    }
}
