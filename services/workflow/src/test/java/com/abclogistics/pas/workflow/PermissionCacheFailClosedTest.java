package com.abclogistics.pas.workflow;

import com.abclogistics.pas.common.security.HeaderAuthenticationFilter;
import com.abclogistics.pas.common.security.PermissionCache;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies M1 fail-closed per mechanics.md:18 — Redis down => PERMISSION_DENIED (403), not permissive.
 * Workflow uses same HeaderAuthenticationFilter as identity, so this covers workflow as well.
 */
class PermissionCacheFailClosedTest {

    @Test
    void headerFilterReturns403WhenPermissionCacheThrows() throws Exception {
        PermissionCache failingCache = mock(PermissionCache.class);
        when(failingCache.resolve(anyCollection())).thenThrow(new RuntimeException("Redis down"));

        ObjectMapper mapper = new ObjectMapper();
        HeaderAuthenticationFilter filter = new HeaderAuthenticationFilter(failingCache, mapper);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "11111111-1111-1111-1111-111111111111");
        request.addHeader("X-Username", "tester");
        request.addHeader("X-Roles", "[\"SYSTEM_ADMIN\"]");

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { throw new AssertionError("chain should not be called when Redis down"); };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        String body = response.getContentAsString();
        assertThat(body).contains("Authorization service unavailable");
    }

    @Test
    void headerFilterAllowsWhenCacheReturnsPermissions() throws Exception {
        PermissionCache okCache = mock(PermissionCache.class);
        when(okCache.resolve(anyCollection())).thenReturn(java.util.Set.of("workflow:configure"));

        ObjectMapper mapper = new ObjectMapper();
        HeaderAuthenticationFilter filter = new HeaderAuthenticationFilter(okCache, mapper);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/workflow-definitions");
        request.addHeader("X-User-Id", "22222222-2222-2222-2222-222222222222");
        request.addHeader("X-Roles", "[\"SYSTEM_ADMIN\"]");

        MockHttpServletResponse response = new MockHttpServletResponse();
        final boolean[] chainCalled = {false};
        FilterChain chain = (req, res) -> chainCalled[0] = true;

        filter.doFilter(request, response, chain);

        assertThat(chainCalled[0]).isTrue();
        assertThat(response.getStatus()).isNotEqualTo(403);
    }
}
