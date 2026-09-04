package com.abclogistics.pas.contract.service;

import com.abclogistics.pas.common.error.UnprocessableEntityException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/** Issues and verifies versioned, tamper-evident insertion-snapshot cursors. */
@Component
public final class PageSnapshotCodec {
    private static final String VERSION = "v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Duration MAX_AGE = Duration.ofHours(1);
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(1);

    private final byte[] secret;
    private final Clock clock;

    @Autowired
    public PageSnapshotCodec(@Value("${contract.pagination-cursor-secret}") String secret) {
        this(secret, Clock.systemUTC());
    }

    PageSnapshotCodec(String secret, Clock clock) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("contract.pagination-cursor-secret must contain at least 32 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public PageSnapshot resolve(String cursor) {
        Instant now = clock.instant();
        if (cursor == null || cursor.isBlank()) {
            return new PageSnapshot(now, encode(now));
        }
        try {
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) throw invalid();
            String signedContent = parts[0] + "." + parts[1];
            byte[] suppliedSignature = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(sign(signedContent), suppliedSignature)) throw invalid();

            Instant instant = Instant.parse(new String(
                    Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            if (instant.isBefore(now.minus(MAX_AGE)) || instant.isAfter(now.plus(FUTURE_TOLERANCE))) {
                throw invalid();
            }
            return new PageSnapshot(instant, cursor);
        } catch (DateTimeException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private String encode(Instant instant) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                instant.toString().getBytes(StandardCharsets.UTF_8));
        String signedContent = VERSION + "." + payload;
        return signedContent + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sign(signedContent));
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Unable to sign page cursor", failure);
        }
    }

    private static UnprocessableEntityException invalid() {
        String message = "The page cursor is invalid or has expired; return to the first page.";
        return new UnprocessableEntityException("INVALID_PAGE_CURSOR", message, message);
    }
}
