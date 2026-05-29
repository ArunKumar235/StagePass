package org.stagepass.paymentservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * HEADER AUTH FILTER
 *
 * Identical in purpose to Event Service and Booking Service's HeaderAuthFilter.
 * Reads X-User-Id and X-Role headers stamped by the API Gateway after JWT
 * validation, and builds a Spring Security Authentication so that
 * SecurityConfig URL rules and @PreAuthorize work correctly.
 *
 * PAYMENT-SPECIFIC CONSIDERATION:
 * The webhook endpoint POST /payments/webhook does NOT go through the Gateway
 * and will NOT have X-User-Id or X-Role headers (Razorpay doesn't send them).
 * This filter handles that gracefully — if headers are absent, it does nothing
 * and Spring Security falls through to the permitAll() rule in SecurityConfig.
 * The webhook's security is handled separately by WebhookSignatureVerifier.
 *
 * This service NEVER parses JWT. It trusts the Gateway completely.
 * If this service is called directly (bypassing Gateway), the headers won't
 * be present and protected routes correctly return 403.
 */
@Component
public class HeaderAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HeaderAuthFilter.class);

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String ROLE_HEADER    = "X-Role";
    private static final String TRACE_HEADER   = "X-Trace-Id";

    @Autowired
    private UserContext userContext;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NotNull HttpServletResponse response,
                                    @NotNull FilterChain filterChain)
            throws ServletException, IOException {

        String userId  = request.getHeader(USER_ID_HEADER);
        String role    = request.getHeader(ROLE_HEADER);
        String traceId = request.getHeader(TRACE_HEADER);

        // Always populate UserContext — traceId needed for all paths including webhook
        if (userId  != null) userContext.setUserId(userId);
        if (role    != null) userContext.setRole(role);
        if (traceId != null) userContext.setTraceId(traceId);

        // No headers → anonymous request (webhook or unauthenticated call)
        // SecurityConfig handles access control — this filter just populates context
        if (userId == null || userId.isBlank()) {
            log.debug("No X-User-Id header for path={} — anonymous/webhook request",
                    request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }

        // Build Spring Security Authentication from Gateway-stamped headers
        String springRole = "ROLE_" + (role != null ? role.toUpperCase() : "USER");
        List<SimpleGrantedAuthority> authorities =
                Collections.singletonList(new SimpleGrantedAuthority(springRole));

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                        userId,       // principal
                        null,         // credentials (already verified by Gateway)
                        authorities   // roles
                );
        auth.setDetails(traceId);

        SecurityContextHolder.getContext().setAuthentication(auth);

        log.debug("Security context set: userId={} role={} path={}",
                userId, role, request.getRequestURI());

        filterChain.doFilter(request, response);
    }
}