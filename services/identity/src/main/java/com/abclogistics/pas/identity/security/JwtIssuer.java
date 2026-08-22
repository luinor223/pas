package com.abclogistics.pas.identity.security;

import com.abclogistics.pas.identity.domain.AppUser;
import com.abclogistics.pas.identity.domain.Role;
import io.jsonwebtoken.Jwts;

import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Issues RS256-signed access tokens. Only identity holds the private key; the edge validates with the public key. */
public class JwtIssuer {

    private final RSAPrivateKey signingKey;
    private final String issuer;
    private final Duration accessTokenTtl;

    public JwtIssuer(RSAPrivateKey signingKey, String issuer, Duration accessTokenTtl) {
        this.signingKey = signingKey;
        this.issuer = issuer;
        this.accessTokenTtl = accessTokenTtl;
    }

    public IssuedToken issue(AppUser user) {
        Instant now = Instant.now();
        Instant exp = now.plus(accessTokenTtl);
        String jti = UUID.randomUUID().toString();

        List<String> roles = user.getRoles().stream().map(Role::getCode).toList();

        String token = Jwts.builder()
                .issuer(issuer)
                .subject(user.getId().toString())
                .id(jti)
                .claim("username", user.getUsername())
                .claim("full_name", user.getFullName())
                .claim("department", user.getDepartment().getCode())
                .claim("roles", roles)
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(exp))
                .signWith(signingKey, Jwts.SIG.RS256)
                .compact();

        return new IssuedToken(token, jti, exp);
    }

    public record IssuedToken(String token, String jti, Instant expiresAt) { }
}
