package com.abclogistics.pas.common.security;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies HeaderAuthenticationFilter — the trust boundary for the edge-injected identity headers.
 * Edge strips client-supplied copies and injects X-User-Id etc after validating RS256; services trust those headers.
 * This test proves the filter's fail-closed and parsing behavior (mechanics.md M1 + 00-registry.md §6).
 */
class HeaderFilterStripTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PermissionCache permissionCache;
    private HeaderAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        permissionCache = mock(PermissionCache.class);
        filter = new HeaderAuthenticationFilter(permissionCache, objectMapper);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingUserIdMeansUnauthenticatedPassesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/users");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(res.getStatus()).isEqualTo(200); // filter did not write error, chain continued
    }

    @Test
    void validHeadersSetAuthenticationWithResolvedPermissions() throws Exception {
        String userId = UUID.randomUUID().toString();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/users");
        req.addHeader("X-User-Id", userId);
        req.addHeader("X-Username", "admin");
        req.addHeader("X-Full-Name", "System Admin");
        req.addHeader("X-Department", "IT");
        req.addHeader("X-Roles", "[\"SYSTEM_ADMIN\",\"DIRECTOR\"]"); // JSON array as edge serializes

        when(permissionCache.resolve(List.of("SYSTEM_ADMIN", "DIRECTOR")))
                .thenReturn(Set.of("customer:read", "approval:act"));

        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isInstanceOf(AuthenticatedUser.class);
        var user = (AuthenticatedUser) auth.getPrincipal();
        assertThat(user.userId().toString()).isEqualTo(userId);
        assertThat(user.roles()).containsExactlyInAnyOrder("SYSTEM_ADMIN", "DIRECTOR");
        assertThat(auth.getAuthorities()).extracting(Object::toString)
                .containsExactlyInAnyOrder("customer:read", "approval:act");
    }

    @Test
    void commaSeparatedRolesAlsoParsed() throws Exception {
        String userId = UUID.randomUUID().toString();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/users");
        req.addHeader("X-User-Id", userId);
        req.addHeader("X-Roles", "SALES_OFFICER, DIRECTOR");

        when(permissionCache.resolve(List.of("SALES_OFFICER", "DIRECTOR"))).thenReturn(Set.of("customer:read"));

        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);
        var user = (AuthenticatedUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(user.roles()).containsExactly("SALES_OFFICER", "DIRECTOR");
    }

    @Test
    void malformedUserIdReturns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/users");
        req.addHeader("X-User-Id", "not-a-uuid");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("Malformed identity headers");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void redisFailureReturns403FailClosed() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/users");
        req.addHeader("X-User-Id", UUID.randomUUID().toString());
        req.addHeader("X-Roles", "[\"SALES_OFFICER\"]");
        when(permissionCache.resolve(anyCollection())).thenThrow(new RuntimeException("Redis down"));

        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("Authorization service unavailable");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void edgeStripsClientHeadersConceptually_absenceMeansUnauthenticated() throws Exception {
        // The edge's removeMissingHeaders=true + headerMap ensures any client-supplied X-User-Id without valid JWT
        // is stripped before reaching the service. From service perspective, this is same as missing header.
        // This test proves that without the header the service treats the request as unauthenticated (later
        // SecurityFilterChain will return 401/403 for protected endpoints).
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/users");
        // no headers
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void authenticatedUserIsAvailableViaSecurityUtils() throws Exception {
        String userId = UUID.randomUUID().toString();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/users");
        req.addHeader("X-User-Id", userId);
        req.addHeader("X-Roles", "[]");
        when(permissionCache.resolve(List.of())).thenReturn(Set.of());

        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);

        // SecurityUtils should resolve current user after filter
        var current = SecurityUtils.currentUser();
        assertThat(current).isPresent();
        assertThat(current.get().userId().toString()).isEqualTo(userId);
        assertThat(SecurityUtils.currentUserId().toString()).isEqualTo(userId);
    }
}
