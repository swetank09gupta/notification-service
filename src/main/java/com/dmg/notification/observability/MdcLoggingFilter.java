package com.dmg.notification.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Injects per-request context into MDC so every log line carries:
 *   requestId   — unique ID for this HTTP request (correlates all log lines for one call)
 *   tenantId    — extracted from the JWT principal
 *   path        — HTTP method + path
 *
 * Example log output:
 *   2026-06-22 10:00:01 INFO  [requestId=abc123 tenantId=t1] DispatchService - Delivered ...
 */
@Component
public class MdcLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("requestId", requestId);
        MDC.put("path", request.getMethod() + " " + request.getRequestURI());

        // tenantId is available after JWT filter runs — set it if present
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof com.dmg.notification.security.UserPrincipal p
                    && p.getTenantId() != null) {
                MDC.put("tenantId", p.getTenantId().toString());
            }
        } catch (Exception ignored) {}

        // Echo the requestId back so callers can correlate their logs
        response.setHeader("X-Request-Id", requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
