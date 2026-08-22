package com.abclogistics.pas.identity.controller;

import com.abclogistics.pas.identity.dto.RolePermissionsRequest;
import com.abclogistics.pas.identity.dto.RoleResponse;
import com.abclogistics.pas.identity.service.RoleService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/roles")
@PreAuthorize("hasAuthority('user:manage')")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    public List<RoleResponse> list() {
        return roleService.list();
    }

    @GetMapping("/{code}")
    public RoleResponse get(@PathVariable String code) {
        return roleService.get(code);
    }

    @PutMapping("/{code}/permissions")
    public RoleResponse replacePermissions(@PathVariable String code,
                                           @Valid @RequestBody RolePermissionsRequest request) {
        return roleService.replacePermissions(code, request.permissionCodes());
    }
}
