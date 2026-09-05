package com.abclogistics.pas.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "redis")
public record RedisKeyProperties(
        String permPrefix
) {
    public String permKey(String roleCode) {
        return permPrefix + roleCode;
    }
}
