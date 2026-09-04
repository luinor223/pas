package com.abclogistics.pas.common.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the inbound {@code X-Correlation-Id} (or mints one), stamps it into the MDC for the request,
 * echoes it on the response, and clears it afterwards. Ordered ahead of the security chain so even
 * rejected requests are correlated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = CorrelationSupport.orNew(request.getHeader(CorrelationSupport.HTTP_HEADER));
        CorrelationSupport.set(id);
        response.setHeader(CorrelationSupport.HTTP_HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            CorrelationSupport.clear();
        }
    }
}
