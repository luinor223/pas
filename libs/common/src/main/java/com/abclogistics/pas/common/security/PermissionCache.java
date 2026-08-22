package com.abclogistics.pas.common.security;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves {@code roles[] -> permissions[]} from the Redis {@code perm:role:{code}} map.
 * Fail-closed: if Redis is unavailable the exception propagates and the caller denies.
 */
@Component
public class PermissionCache {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final StringRedisTemplate redis;
    private final RedisKeyProperties keys;
    private final ObjectMapper objectMapper;

    public PermissionCache(StringRedisTemplate redis, RedisKeyProperties keys, ObjectMapper objectMapper) {
        this.redis = redis;
        this.keys = keys;
        this.objectMapper = objectMapper;
    }

    public Set<String> resolve(Collection<String> roleCodes) {
        if (roleCodes.isEmpty()) {
            return Set.of();
        }
        List<String> redisKeys = roleCodes.stream().map(keys::permKey).toList();
        List<String> values = redis.opsForValue().multiGet(redisKeys);

        Set<String> permissions = new HashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null) {
                    permissions.addAll(parse(value));
                }
            }
        }
        return permissions;
    }

    private List<String> parse(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed permission cache entry", e);
        }
    }
}
