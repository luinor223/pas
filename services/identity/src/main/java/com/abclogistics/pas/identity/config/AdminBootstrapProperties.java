package com.abclogistics.pas.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bootstrap.admin")
public record AdminBootstrapProperties(
        String username,
        String password,
        String email,
        String fullName
) {
}
