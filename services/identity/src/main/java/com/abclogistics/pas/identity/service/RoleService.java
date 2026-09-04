package com.abclogistics.pas.identity.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.identity.domain.Permission;
import com.abclogistics.pas.identity.domain.Role;
import com.abclogistics.pas.identity.dto.RoleResponse;
import com.abclogistics.pas.identity.repository.PermissionRepository;
import com.abclogistics.pas.identity.repository.RoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

@Service
public class RoleService {

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final AuditRecorder audit;
    private final PermissionCacheWriter cacheWriter;

    public RoleService(RoleRepository roles, PermissionRepository permissions,
                       AuditRecorder audit, PermissionCacheWriter cacheWriter) {
        this.roles = roles;
        this.permissions = permissions;
        this.audit = audit;
        this.cacheWriter = cacheWriter;
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> list() {
        return roles.findAll().stream()
                .map(RoleResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public RoleResponse get(String code) {
        return RoleResponse.from(loadWithPermissions(code));
    }

    /**
     * Replaces a role's permission set under a row lock, so concurrent replaces serialize.
     * The Redis cache is rewritten after commit — best-effort; the hourly sweep is the backstop.
     */
    @Transactional
    public RoleResponse replacePermissions(String code, List<String> permissionCodes) {
        Role role = roles.findWithLockByCode(code)
                .orElseThrow(() -> new NotFoundException("Unknown role: " + code));

        List<String> before = role.getPermissions().stream()
                .map(Permission::getCode)
                .sorted()
                .toList();

        List<String> distinctCodes = permissionCodes.stream().distinct().toList();
        List<Permission> resolved = permissions.findByCodeIn(distinctCodes);
        if (resolved.size() != distinctCodes.size()) {
            throw new NotFoundException("One or more unknown permission codes");
        }

        role.setPermissions(new HashSet<>(resolved));
        List<String> after = resolved.stream().map(Permission::getCode).sorted().toList();
        if (!before.equals(after)) {
            audit.record("ROLE", role.getId(), role.getCode(), "role.permissions_replaced",
                    null, null, null,
                    Map.of("permissions", Map.of("from", before, "to", after)));
        }

        rewriteCacheAfterCommit(code);
        return RoleResponse.from(role);
    }

    private Role loadWithPermissions(String code) {
        return roles.findByCode(code)
                .orElseThrow(() -> new NotFoundException("Unknown role: " + code));
    }

    private void rewriteCacheAfterCommit(String code) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheWriter.writeByCode(code);
            }
        });
    }
}
