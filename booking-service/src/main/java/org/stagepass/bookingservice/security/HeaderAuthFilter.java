package org.stagepass.bookingservice.security;

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
 * Identical in purpose to Event Service's HeaderAuthFilter.
 * Reads X-User-Id and X-Role headers stamped by the Gateway after JWT
 * validation, and builds a Spring Security Authentication object so that
 * @PreAuthorize and SecurityConfig URL rules work correctly.
 *
 * This service NEVER parses JWT — it trusts the Gateway completely.
 * If this service is called directly (bypassing Gateway), the headers
 * won't be present and all protected routes correctly return 403.
 *
 * Also populates UserContext (@RequestScope bean) so BookingService and
 * CancellationService can read the current userId without coupling to
 * Spring Security internals.
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

        // Always populate UserContext — traceId is needed even for unauthenticated paths
        if (userId  != null) userContext.setUserId(userId);
        if (role    != null) userContext.setRole(role);
        if (traceId != null) userContext.setTraceId(traceId);

        // No headers → anonymous request → SecurityConfig blocks protected routes
        if (userId == null || userId.isBlank()) {
            log.debug("No X-User-Id header — anonymous for path: {}",
                    request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }

        // Build Spring Security Authentication from gateway-stamped headers
        String springRole = "ROLE_" + (role != null ? role.toUpperCase() : "USER");
        List<SimpleGrantedAuthority> authorities =
                Collections.singletonList(new SimpleGrantedAuthority(springRole));

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                        userId, null, authorities);
        auth.setDetails(traceId);

        SecurityContextHolder.getContext().setAuthentication(auth);

        log.debug("Security context set: userId={} role={} traceId={}",
                userId, role, traceId);

        filterChain.doFilter(request, response);
    }
}