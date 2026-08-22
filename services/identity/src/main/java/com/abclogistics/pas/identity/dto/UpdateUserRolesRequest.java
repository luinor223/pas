package com.abclogistics.pas.identity.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record UpdateUserRolesRequest(
        @NotEmpty List<String> roleCodes
) {
}
