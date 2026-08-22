package com.abclogistics.pas.identity.dto;

import java.util.List;

public record RolePermissionsRequest(
        List<String> permissionCodes
) {
}
