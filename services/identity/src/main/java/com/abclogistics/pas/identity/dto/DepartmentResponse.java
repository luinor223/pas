package com.abclogistics.pas.identity.dto;

import com.abclogistics.pas.identity.domain.Department;

import java.util.UUID;

public record DepartmentResponse(UUID id, String code, String name) {
    public static DepartmentResponse from(Department d) {
        return new DepartmentResponse(d.getId(), d.getCode(), d.getName());
    }
}
