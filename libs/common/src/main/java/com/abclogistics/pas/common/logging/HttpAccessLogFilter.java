package com.abclogistics.pas.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * One line per HTTP request: {@code METHOD /path -> status (Nms)}, at INFO (WARN for 5xx). Ordered just
 * inside the correlation filter so every line carries the correlation id. Actuator traffic is skipped to
 * keep health polling out of the log. Silence with {@code logging.level.access.http=WARN}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class HttpAccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("access.http");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            int status = response.getStatus();
            if (status >= 500) {
                log.warn("{} {} -> {} ({}ms)", request.getMethod(), request.getRequestURI(), status, ms);
            } else {
                log.info("{} {} -> {} ({}ms)", request.getMethod(), request.getRequestURI(), status, ms);
            }
        }
    }
}
