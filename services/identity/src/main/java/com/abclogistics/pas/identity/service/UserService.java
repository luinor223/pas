package com.abclogistics.pas.identity.service;

import com.abclogistics.pas.common.audit.AuditRecorder;
import com.abclogistics.pas.common.error.ConflictException;
import com.abclogistics.pas.common.error.NotFoundException;
import com.abclogistics.pas.common.security.SecurityUtils;
import com.abclogistics.pas.identity.domain.AppUser;
import com.abclogistics.pas.identity.domain.Department;
import com.abclogistics.pas.identity.domain.Role;
import com.abclogistics.pas.identity.dto.CreateUserRequest;
import com.abclogistics.pas.identity.dto.UserResponse;
import com.abclogistics.pas.identity.repository.AppUserRepository;
import com.abclogistics.pas.identity.repository.DepartmentRepository;
import com.abclogistics.pas.identity.repository.RoleRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class UserService {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final DepartmentRepository departments;
    private final PasswordEncoder passwordEncoder;
    private final AuditRecorder audit;
    private final RefreshTokenService refreshTokens;

    public UserService(AppUserRepository users, RoleRepository roles, DepartmentRepository departments,
                       PasswordEncoder passwordEncoder, AuditRecorder audit, RefreshTokenService refreshTokens) {
        this.users = users;
        this.roles = roles;
        this.departments = departments;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.refreshTokens = refreshTokens;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return users.findAll().stream().map(UserResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return UserResponse.from(load(id));
    }

    @Transactional
    public UserResponse update(UUID id, com.abclogistics.pas.identity.dto.UpdateUserRequest request) {
        AppUser user = load(id);
        String newEmail = request.email();
        if (!user.getEmail().equalsIgnoreCase(newEmail) && users.existsByEmail(newEmail)) {
            throw new ConflictException("Email already exists: " + newEmail);
        }
        Department department = departments.findByCode(request.departmentCode())
                .orElseThrow(() -> new NotFoundException("Unknown department: " + request.departmentCode()));

        Map<String, Object> changes = new LinkedHashMap<>();
        addChange(changes, "fullName", user.getFullName(), request.fullName());
        addChange(changes, "email", user.getEmail(), newEmail);
        addChange(changes, "department", user.getDepartment().getCode(), request.departmentCode());

        user.setFullName(request.fullName());
        user.setEmail(newEmail);
        user.setDepartment(department);
        user.setUpdatedBy(SecurityUtils.currentUserId());
        if (!changes.isEmpty()) {
            audit.record("USER", user.getId(), user.getUsername(), "user.updated",
                    null, null, null, changes);
        }
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public List<String> permissionsForUser(UUID userId) {
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("Unknown user: " + userId));
        return resolvePermissions(user);
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        if (users.existsByUsername(request.username())) {
            throw new ConflictException("Username already exists: " + request.username());
        }
        if (users.existsByEmail(request.email())) {
            throw new ConflictException("Email already exists: " + request.email());
        }
        Department department = departments.findByCode(request.departmentCode())
                .orElseThrow(() -> new NotFoundException("Unknown department: " + request.departmentCode()));

        AppUser user = AppUser.create(
                request.username(), request.email(),
                passwordEncoder.encode(request.password()),
                request.fullName(), department);
        user.getRoles().addAll(resolveRoles(request.roleCodes()));
        stampActor(user);
        users.save(user);

        audit.record("USER", user.getId(), user.getUsername(), "user.created",
                null, "ACTIVE", null, Map.of(
                        "username", user.getUsername(),
                        "fullName", user.getFullName(),
                        "email", user.getEmail(),
                        "department", department.getCode(),
                        "roles", roleCodes(user.getRoles())));
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse setRoles(UUID id, List<String> roleCodes) {
        AppUser user = load(id);
        List<String> before = roleCodes(user.getRoles());
        user.getRoles().clear();
        user.getRoles().addAll(resolveRoles(roleCodes));
        user.setUpdatedBy(SecurityUtils.currentUserId());
        List<String> after = roleCodes(user.getRoles());
        if (!before.equals(after)) {
            audit.record("USER", user.getId(), user.getUsername(), "user.roles_updated",
                    null, null, null, Map.of("roles", Map.of("from", before, "to", after)));
        }
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse setEnabled(UUID id, boolean enabled) {
        AppUser user = load(id);
        String before = user.getStatus().name();
        if (enabled) {
            user.enable();
        } else {
            user.disable();
            refreshTokens.revokeAllForUser(user.getId());
        }
        user.setUpdatedBy(SecurityUtils.currentUserId());
        audit.record("USER", user.getId(), user.getUsername(), enabled ? "user.enabled" : "user.disabled",
                before, user.getStatus().name(), null, Map.of());
        return UserResponse.from(user);
    }

    private AppUser load(UUID id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("Unknown user: " + id));
    }

    private List<String> resolvePermissions(AppUser user) {
        return PermissionResolver.fromUser(user);
    }

    private Set<Role> resolveRoles(List<String> roleCodes) {
        Set<Role> resolved = new LinkedHashSet<>();
        for (String code : roleCodes) {
            resolved.add(roles.findByCode(code)
                    .orElseThrow(() -> new NotFoundException("Unknown role: " + code)));
        }
        return resolved;
    }

    private List<String> roleCodes(Set<Role> assignedRoles) {
        return assignedRoles.stream().map(Role::getCode).sorted().toList();
    }

    private void addChange(Map<String, Object> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.put(field, Map.of("from", before, "to", after));
        }
    }

    private void stampActor(AppUser user) {
        UUID actor = SecurityUtils.currentUserId();
        user.setCreatedBy(actor);
        user.setUpdatedBy(actor);
    }
}
