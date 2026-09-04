package com.abclogistics.pas.common.security;

import com.abclogistics.pas.common.api.ApiError;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Builds the security principal from the identity headers the edge injects after it
 * validates the JWT. The edge strips these headers from client requests, so their
 * presence means an authenticated caller. Permissions are resolved from the Redis map.
 */
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HeaderAuthenticationFilter.class);
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "(?i)^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$");
    private static final Pattern ROLE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

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
            if (!CANONICAL_UUID.matcher(userId).matches()) {
                throw new IllegalArgumentException("User id is not a canonical UUID");
            }
            user = new AuthenticatedUser(
                    UUID.fromString(userId),
                    request.getHeader(USERNAME),
                    request.getHeader(FULL_NAME),
                    request.getHeader(DEPARTMENT),
                    parseRoles(request.getHeader(ROLES)));
        } catch (IllegalArgumentException e) {
            log.warn("Rejected malformed identity headers for {} {}",
                    request.getMethod(), request.getRequestURI(), e);
            writeError(request, response, HttpStatus.UNAUTHORIZED, "INVALID_IDENTITY", "Malformed identity headers");
            return;
        }

        Set<String> permissions;
        try {
            permissions = permissionCache.resolve(user.roles());
        } catch (RuntimeException e) {
            log.error("Permission cache unavailable for {} {}",
                    request.getMethod(), request.getRequestURI(), e);
            writeError(request, response, HttpStatus.FORBIDDEN, "AUTHORIZATION_UNAVAILABLE", "Authorization service unavailable");
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
        List<String> roles;
        if (trimmed.startsWith("[")) {
            try {
                roles = objectMapper.readValue(trimmed, new TypeReference<List<String>>() { });
            } catch (Exception e) {
                throw new IllegalArgumentException("Malformed roles header", e);
            }
        } else {
            roles = Arrays.stream(trimmed.split(",", -1))
                    .map(String::trim)
                    .toList();
        }
        if (roles == null || roles.stream().anyMatch(role -> role == null || !ROLE_CODE.matcher(role).matches())) {
            throw new IllegalArgumentException("Roles must be nonblank role codes");
        }
        return List.copyOf(roles);
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response,
                            HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError body = ApiError.of(status.value(), status.getReasonPhrase(), code, message, request.getRequestURI());
        objectMapper.writeValue(response.getWriter(), body);
    }
}
