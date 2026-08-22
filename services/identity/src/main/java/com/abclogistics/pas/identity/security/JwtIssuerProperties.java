package com.abclogistics.pas.identity.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "jwt")
public record JwtIssuerProperties(
        String issuer,
        String privateKeyPath,
        Duration accessTokenTtl,
        Duration refreshTokenTtl
) {
}
