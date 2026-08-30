package com.abclogistics.pas.operations;

import com.abclogistics.pas.common.security.HeaderAuthenticationFilter;
import com.abclogistics.pas.common.security.PermissionCache;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M1 fail-closed via HeaderAuthenticationFilter: Redis down -> 403, not permissive.
 * Mirrors libs:common PermissionCacheFailClosedTest but proves operations wires it correctly.
 */
class PermissionCacheFailClosedTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void filterReturns403WhenPermissionCacheThrows() throws Exception {
        PermissionCache cache = mock(PermissionCache.class);
        when(cache.resolve(anyList())).thenThrow(new RuntimeException("Redis down"));

        HeaderAuthenticationFilter filter = new HeaderAuthenticationFilter(cache, objectMapper);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "11111111-1111-1111-1111-111111111111");
        req.addHeader("X-Username", "ops");
        req.addHeader("X-Roles", "[\"OPS_OFFICER\"]");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (r, s) -> {};

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("Authorization service unavailable");
    }

    @Test
    void filterPassesWhenCacheHealthy() throws Exception {
        PermissionCache cache = mock(PermissionCache.class);
        when(cache.resolve(anyList())).thenReturn(java.util.Set.of("volume:read"));

        HeaderAuthenticationFilter filter = new HeaderAuthenticationFilter(cache, objectMapper);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "11111111-1111-1111-1111-111111111111");
        req.addHeader("X-Roles", "[\"OPS_OFFICER\"]");
        MockHttpServletResponse res = new MockHttpServletResponse();
        final boolean[] chainCalled = {false};
        FilterChain chain = (r, s) -> chainCalled[0] = true;

        filter.doFilter(req, res, chain);

        assertThat(chainCalled[0]).isTrue();
        assertThat(res.getStatus()).isNotEqualTo(403);
    }
}
