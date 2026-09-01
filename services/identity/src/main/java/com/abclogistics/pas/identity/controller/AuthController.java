package com.abclogistics.pas.identity.controller;

import com.abclogistics.pas.common.error.UnauthorizedException;
import com.abclogistics.pas.identity.dto.LoginRequest;
import com.abclogistics.pas.identity.dto.LoginResponse;
import com.abclogistics.pas.identity.dto.RefreshRequest;
import com.abclogistics.pas.identity.dto.TokenResponse;
import com.abclogistics.pas.identity.security.AuthCookieWriter;
import com.abclogistics.pas.identity.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthCookieWriter cookies;

    public AuthController(AuthService authService, AuthCookieWriter cookies) {
        this.authService = authService;
        this.cookies = cookies;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        LoginResponse result = authService.login(request);
        cookies.writeSession(response, result.accessToken(), result.refreshToken());
        return result;
    }

    // Refresh token comes from the pas_rt cookie; the body is a transitional fallback.
    @PostMapping("/refresh")
    public TokenResponse refresh(@CookieValue(name = AuthCookieWriter.REFRESH_COOKIE, required = false) String cookieToken,
                                 @RequestBody(required = false) RefreshRequest request,
                                 HttpServletResponse response) {
        String token = refreshToken(cookieToken, request);
        if (token == null) {
            throw new UnauthorizedException("Missing refresh token");
        }
        TokenResponse result = authService.refresh(token);
        cookies.writeSession(response, result.accessToken(), result.refreshToken());
        return result;
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name = AuthCookieWriter.REFRESH_COOKIE, required = false) String cookieToken,
                       @RequestBody(required = false) RefreshRequest request,
                       HttpServletResponse response) {
        String token = refreshToken(cookieToken, request);
        if (token != null) {
            authService.logout(token);
        }
        cookies.clear(response);
    }

    private static String refreshToken(String cookieToken, RefreshRequest request) {
        if (cookieToken != null && !cookieToken.isBlank()) {
            return cookieToken;
        }
        return request != null ? request.refreshToken() : null;
    }
}
