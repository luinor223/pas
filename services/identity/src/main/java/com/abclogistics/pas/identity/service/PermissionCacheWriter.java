package com.abclogistics.pas.identity.service;

import com.abclogistics.pas.common.security.RedisKeyProperties;
import com.abclogistics.pas.identity.domain.Permission;
import com.abclogistics.pas.identity.domain.Role;
import com.abclogistics.pas.identity.repository.RoleRepository;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Owns the Redis {@code perm:role:{code}} map. identity is the only writer.
 * Rewrites all roles at startup and hourly, and one role immediately after its
 * permissions change. Failures are logged, not thrown — the next sweep self-heals.
 */
@Service
public class PermissionCacheWriter {

    private static final Logger log = LoggerFactory.getLogger(PermissionCacheWriter.class);

    private final RoleRepository roles;
    private final StringRedisTemplate redis;
    private final RedisKeyProperties keys;
    private final ObjectMapper objectMapper;

    public PermissionCacheWriter(RoleRepository roles, StringRedisTemplate redis,
                                 RedisKeyProperties keys, ObjectMapper objectMapper) {
        this.roles = roles;
        this.redis = redis;
        this.keys = keys;
        this.objectMapper = objectMapper;
    }

    /** Best-effort write of one role's permission set. */
    public void write(Role role) {
        List<String> codes = role.getPermissions().stream().map(Permission::getCode).sorted().toList();
        try {
            redis.opsForValue().set(keys.permKey(role.getCode()), objectMapper.writeValueAsString(codes),
                    keys.permCacheTtl());
        } catch (Exception e) {
            log.warn("Failed to write permission cache for role {}: {}", role.getCode(), e.getMessage());
        }
    }

    /** Reload one role and rewrite its cache entry. */
    @Transactional(readOnly = true)
    public void writeByCode(String code) {
        roles.findByCode(code).ifPresent(this::write);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void warmup() {
        refreshAllInternal("warmup");
    }

    @Scheduled(fixedRate = 3_600_000)
    @Transactional(readOnly = true)
    public void scheduledRefresh() {
        refreshAllInternal("scheduled");
    }

    // Kept for backward compat if called directly; delegates to internal.
    public void refreshAll() {
        refreshAllInternal("manual");
    }

    private void refreshAllInternal(String trigger) {
        for (Role role : roles.findAll()) {
            role.getPermissions().size(); // initialize before serialization
            write(role);
        }
        log.debug("Permission cache refreshed via {}", trigger);
    }
}
