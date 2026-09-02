package com.abclogistics.pas.identity.service;

import com.abclogistics.pas.identity.domain.AppUser;
import com.abclogistics.pas.identity.domain.Role;

import java.util.Collection;
import java.util.List;

/**
 * Single place to derive permission codes from roles. Used by login, me, and permissionsForUser.
 */
public final class PermissionResolver {

    private PermissionResolver() { }

    public static List<String> fromUser(AppUser user) {
        return fromRoles(user.getRoles());
    }

    public static List<String> fromRoles(Collection<Role> roles) {
        return roles.stream()
                .flatMap(r -> r.getPermissions().stream())
                .map(p -> p.getCode())
                .distinct()
                .sorted()
                .toList();
    }
}
