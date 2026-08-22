package com.abclogistics.pas.identity.service;

import com.abclogistics.pas.identity.domain.AppUser;
import com.abclogistics.pas.identity.domain.RefreshToken;
import com.abclogistics.pas.identity.repository.AppUserRepository;
import com.abclogistics.pas.identity.repository.RefreshTokenRepository;
import com.abclogistics.pas.identity.security.JwtIssuerProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository tokens;
    private final AppUserRepository users;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    public RefreshTokenService(RefreshTokenRepository tokens, AppUserRepository users, JwtIssuerProperties jwtProperties) {
        this.tokens = tokens;
        this.users = users;
        this.ttl = jwtProperties.refreshTokenTtl();
    }

    /** Starts a new token family for a fresh login. */
    @Transactional
    public Issued issueForLogin(UUID userId) {
        return persist(userId, UUID.randomUUID());
    }

    /**
     * Validates the presented token and, if good, rotates it within its family.
     * A revoked token being presented again means it was stolen and replayed: the
     * whole family is revoked. A disabled account also loses its family.
     */
    @Transactional
    public Outcome rotate(String rawToken) {
        Instant now = Instant.now();
        Optional<RefreshToken> found = tokens.findByTokenHash(hash(rawToken));
        if (found.isEmpty()) {
            return Outcome.rejected();
        }
        RefreshToken current = found.get();

        if (current.isRevoked()) {
            tokens.revokeFamily(current.getFamilyId(), now);
            return Outcome.rejected();
        }
        if (current.isExpired(now)) {
            return Outcome.rejected();
        }

        AppUser user = users.findWithGraphById(current.getUserId())
                .filter(AppUser::isActive)
                .orElse(null);
        if (user == null) {
            tokens.revokeFamily(current.getFamilyId(), now);
            return Outcome.rejected();
        }

        Issued next = persist(user.getId(), current.getFamilyId());
        current.revoke(now);
        current.setReplacedBy(next.tokenId());
        return Outcome.rotated(user, next);
    }

    /** Ends the session behind this token by revoking its whole family. */
    @Transactional
    public void revoke(String rawToken) {
        tokens.findByTokenHash(hash(rawToken))
                .ifPresent(t -> tokens.revokeFamily(t.getFamilyId(), Instant.now()));
    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        tokens.revokeAllForUser(userId, Instant.now());
    }

    private Issued persist(UUID userId, UUID familyId) {
        Instant now = Instant.now();
        String raw = generate();
        RefreshToken saved = tokens.saveAndFlush(
                RefreshToken.create(userId, familyId, hash(raw), now, now.plus(ttl)));
        return new Issued(saved.getId(), raw, saved.getExpiresAt());
    }

    private String generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Issued(UUID tokenId, String rawToken, Instant expiresAt) { }

    public record Outcome(AppUser user, Issued refreshToken) {
        static Outcome rotated(AppUser user, Issued refreshToken) {
            return new Outcome(user, refreshToken);
        }

        static Outcome rejected() {
            return new Outcome(null, null);
        }

        public boolean isRotated() {
            return user != null;
        }
    }
}
