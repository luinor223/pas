package com.abclogistics.pas.identity.controller.http;

import com.abclogistics.pas.identity.dto.PermissionResponse;
import com.abclogistics.pas.identity.repository.PermissionRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/permissions")
@PreAuthorize("hasAuthority('user:manage')")
public class PermissionController {

    private final PermissionRepository permissions;

    public PermissionController(PermissionRepository permissions) {
        this.permissions = permissions;
    }

    @GetMapping
    public List<PermissionResponse> list() {
        return permissions.findAll().stream()
                .sorted(Comparator.comparing(com.abclogistics.pas.identity.domain.Permission::getCode))
                .map(PermissionResponse::from)
                .toList();
    }
}
