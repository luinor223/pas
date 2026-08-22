package com.abclogistics.pas.common.security;

import com.abclogistics.pas.common.error.ApiError;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the security principal from the identity headers the edge injects after it
 * validates the JWT. The edge strips these headers from client requests, so their
 * presence means an authenticated caller. Permissions are resolved from the Redis map.
 */
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    private static final String USER_ID = "X-User-Id";
    private static final String USERNAME = "X-Username";
    private static final String FULL_NAME = "X-Full-Name";
    private static final String DEPARTMENT = "X-Department";
    private static final String ROLES = "X-Roles";

    private final PermissionCache permissionCache;
    private final ObjectMapper objectMapper;

    public HeaderAuthenticationFilter(PermissionCache permissionCache, ObjectMapper objectMapper) {
        this.permissionCache = permissionCache;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String userId = request.getHeader(USER_ID);
        if (userId == null || userId.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        AuthenticatedUser user;
        try {
            user = new AuthenticatedUser(
                    UUID.fromString(userId),
                    request.getHeader(USERNAME),
                    request.getHeader(FULL_NAME),
                    request.getHeader(DEPARTMENT),
                    parseRoles(request.getHeader(ROLES)));
        } catch (IllegalArgumentException e) {
            writeError(request, response, HttpStatus.UNAUTHORIZED, "Malformed identity headers");
            return;
        }

        Set<String> permissions;
        try {
            permissions = permissionCache.resolve(user.roles());
        } catch (RuntimeException e) {
            writeError(request, response, HttpStatus.FORBIDDEN, "Authorization service unavailable");
            return;
        }

        List<SimpleGrantedAuthority> authorities = permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        chain.doFilter(request, response);
    }

    /** The edge serializes the {@code roles} array claim as a JSON array, e.g. {@code ["SALES_OFFICER"]}. */
    private List<String> parseRoles(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        String trimmed = header.trim();
        if (trimmed.startsWith("[")) {
            try {
                return objectMapper.readValue(trimmed, new TypeReference<List<String>>() { });
            } catch (RuntimeException e) {
                return List.of();
            }
        }
        return Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response,
                            HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = ApiError.of(status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
