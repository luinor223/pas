package com.abclogistics.pas.identity.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Writes the session as HttpOnly cookies: pas_at (access JWT, all paths) and pas_rt (refresh,
 *  auth path only). SameSite=Lax; secure off for local http. */
@Component
public class AuthCookieWriter {

    public static final String ACCESS_COOKIE = "pas_at";
    public static final String REFRESH_COOKIE = "pas_rt";
    private static final String REFRESH_PATH = "/api/v1/auth";

    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final boolean secure;

    public AuthCookieWriter(JwtIssuerProperties jwtProperties,
                            @Value("${auth.cookie.secure:false}") boolean secure) {
        this.accessTtl = jwtProperties.accessTokenTtl();
        this.refreshTtl = jwtProperties.refreshTokenTtl();
        this.secure = secure;
    }

    public void writeSession(HttpServletResponse response, String accessToken, String refreshToken) {
        add(response, cookie(ACCESS_COOKIE, accessToken, "/", accessTtl));
        add(response, cookie(REFRESH_COOKIE, refreshToken, REFRESH_PATH, refreshTtl));
    }

    public void clear(HttpServletResponse response) {
        add(response, cookie(ACCESS_COOKIE, "", "/", Duration.ZERO));
        add(response, cookie(REFRESH_COOKIE, "", REFRESH_PATH, Duration.ZERO));
    }

    private ResponseCookie cookie(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
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
