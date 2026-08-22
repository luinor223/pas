package com.abclogistics.pas.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "redis")
public record RedisKeyProperties(
        String permPrefix,
        Duration permCacheTtl
) {
    public String permKey(String roleCode) {
        return permPrefix + roleCode;
    }
}
