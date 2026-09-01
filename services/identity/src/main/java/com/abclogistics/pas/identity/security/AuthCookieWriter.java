package com.abclogistics.pas.identity.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/** Writes the session as cookies: pas_at (access JWT) and pas_rt (refresh, auth path only) are
 *  HttpOnly; pas_csrf is JS-readable so the SPA can echo it as the X-CSRF-Token header for the
 *  edge's double-submit check. SameSite=Lax; secure off for local http. */
@Component
public class AuthCookieWriter {

    public static final String ACCESS_COOKIE = "pas_at";
    public static final String REFRESH_COOKIE = "pas_rt";
    public static final String CSRF_COOKIE = "pas_csrf";
    private static final String REFRESH_PATH = "/api/v1/auth";

    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final boolean secure;
    private final SecureRandom random = new SecureRandom();

    public AuthCookieWriter(JwtIssuerProperties jwtProperties,
                            @Value("${auth.cookie.secure:false}") boolean secure) {
        this.accessTtl = jwtProperties.accessTokenTtl();
        this.refreshTtl = jwtProperties.refreshTokenTtl();
        this.secure = secure;
    }

    public void writeSession(HttpServletResponse response, String accessToken, String refreshToken) {
        add(response, cookie(ACCESS_COOKIE, accessToken, "/", accessTtl, true));
        add(response, cookie(REFRESH_COOKIE, refreshToken, REFRESH_PATH, refreshTtl, true));
        add(response, cookie(CSRF_COOKIE, newCsrfToken(), "/", refreshTtl, false));
    }

    public void clear(HttpServletResponse response) {
        add(response, cookie(ACCESS_COOKIE, "", "/", Duration.ZERO, true));
        add(response, cookie(REFRESH_COOKIE, "", REFRESH_PATH, Duration.ZERO, true));
        add(response, cookie(CSRF_COOKIE, "", "/", Duration.ZERO, false));
    }

    private String newCsrfToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private ResponseCookie cookie(String name, String value, String path, Duration maxAge, boolean httpOnly) {
        return ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(secure)
                .sameSite("Lax")
                .path(path)
                .maxAge(maxAge)
                .build();
    }

    private void add(HttpServletResponse response, ResponseCookie cookie) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
