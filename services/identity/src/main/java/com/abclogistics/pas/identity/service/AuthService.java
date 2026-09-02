package com.abclogistics.pas.identity.service;

import com.abclogistics.pas.common.error.UnauthorizedException;
import com.abclogistics.pas.identity.domain.AppUser;
import com.abclogistics.pas.identity.dto.LoginRequest;
import com.abclogistics.pas.identity.dto.LoginResponse;
import com.abclogistics.pas.identity.dto.TokenResponse;
import com.abclogistics.pas.identity.dto.UserSummary;
import com.abclogistics.pas.identity.repository.AppUserRepository;
import com.abclogistics.pas.identity.security.JwtIssuer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuthService {

    private static final String BEARER = "Bearer";

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtIssuer jwtIssuer;
    private final RefreshTokenService refreshTokens;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder, JwtIssuer jwtIssuer,
                       RefreshTokenService refreshTokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtIssuer = jwtIssuer;
        this.refreshTokens = refreshTokens;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        AppUser user = users.findByUsername(request.username())
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .filter(AppUser::isActive)
                .orElseThrow(() -> new UnauthorizedException("Invalid username or password"));

        user.recordLogin(Instant.now());

        JwtIssuer.IssuedToken access = jwtIssuer.issue(user);
        RefreshTokenService.Issued refresh = refreshTokens.issueForLogin(user.getId());

        // permissions derived from roles + universal notification:read
        java.util.List<String> perms = user.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .map(p -> p.getCode())
                .distinct()
                .sorted()
                .toList();
        java.util.List<String> allPerms = new java.util.ArrayList<>(perms);
        if (!allPerms.contains("notification:read")) {
            allPerms.add("notification:read");
            allPerms.sort(String::compareTo);
        }

        return new LoginResponse(
                access.token(),
                refresh.rawToken(),
                BEARER,
                access.expiresAt(),
                UserSummary.from(user, allPerms));
    }

    /** Exchanges a valid refresh token for a fresh access token and a rotated refresh token. */
    public TokenResponse refresh(String refreshToken) {
        RefreshTokenService.Outcome outcome = refreshTokens.rotate(refreshToken);
        if (!outcome.isRotated()) {
            throw new UnauthorizedException("Invalid refresh token");
        }
        JwtIssuer.IssuedToken access = jwtIssuer.issue(outcome.user());
        return new TokenResponse(
                access.token(),
                outcome.refreshToken().rawToken(),
                BEARER,
                access.expiresAt());
    }

    public void logout(String refreshToken) {
        refreshTokens.revoke(refreshToken);
    }
}
