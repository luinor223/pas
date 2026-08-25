package com.abclogistics.pas.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M1 permission cache — fail-closed: Redis unavailable ⇒ PERMISSION_DENIED via exception propagation,
 * never a permissive default. The edge filter maps this to 403.
 */
class PermissionCacheFailClosedTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resolveThrowsWhenRedisUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.multiGet(anyList())).thenThrow(new RuntimeException("Redis down"));

        RedisKeyProperties keys = new RedisKeyProperties("perm:role:", java.time.Duration.ofHours(6));
        PermissionCache cache = new PermissionCache(redis, keys, objectMapper);

        assertThatThrownBy(() -> cache.resolve(List.of("SALES_OFFICER")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Redis down");
    }

    @Test
    void resolveReturnsUnionOfRolePermissionsWhenRedisHealthy() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.multiGet(List.of("perm:role:SALES_OFFICER", "perm:role:DIRECTOR")))
                .thenReturn(List.of("[\"customer:read\",\"customer:write\"]", "[\"customer:read\",\"approval:act\"]"));

        RedisKeyProperties keys = new RedisKeyProperties("perm:role:", java.time.Duration.ofHours(6));
        PermissionCache cache = new PermissionCache(redis, keys, objectMapper);

        var perms = cache.resolve(List.of("SALES_OFFICER", "DIRECTOR"));
        // union
        org.assertj.core.api.Assertions.assertThat(perms)
                .containsExactlyInAnyOrder("customer:read", "customer:write", "approval:act");
    }

    @Test
    void resolveEmptyRolesReturnsEmptyWithoutRedisCall() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisKeyProperties keys = new RedisKeyProperties("perm:role:", java.time.Duration.ofHours(6));
        PermissionCache cache = new PermissionCache(redis, keys, objectMapper);

        var perms = cache.resolve(List.of());
        org.assertj.core.api.Assertions.assertThat(perms).isEmpty();
        // no interaction with redis
        org.mockito.Mockito.verifyNoInteractions(redis);
    }

    @Test
    void resolveIgnoresMissingKeysGracefully() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        // one role missing => null value (List.of(null) is not allowed, so use singletonList)
        when(ops.multiGet(List.of("perm:role:UNKNOWN")))
                .thenReturn(java.util.Collections.singletonList(null));

        RedisKeyProperties keys = new RedisKeyProperties("perm:role:", java.time.Duration.ofHours(6));
        PermissionCache cache = new PermissionCache(redis, keys, objectMapper);

        var perms = cache.resolve(List.of("UNKNOWN"));
        org.assertj.core.api.Assertions.assertThat(perms).isEmpty();
    }
}
