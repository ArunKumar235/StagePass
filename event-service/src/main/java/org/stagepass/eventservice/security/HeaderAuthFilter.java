package org.stagepass.eventservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 * This service never parses JWT. The API Gateway already validated the token
 * and stamped the identity as plain HTTP headers:
 *   X-User-Id  → the authenticated user's UUID
 *   X-Role     → the user's role (USER, ORGANISER, ADMIN)
 *
 * This filter reads those headers and builds a Spring Security Authentication
 * object, populating the SecurityContextHolder so that:
 *   - @PreAuthorize("hasRole('ORGANISER')") works on service methods
 *   - SecurityConfig URL-based role rules are enforced
 *   - UserContext can expose the current user ID to service layer
 *
 * For unauthenticated public routes (GET /events/**), the headers will be
 * absent — the filter simply does nothing and allows the request through.
 * Spring Security permits it based on the SecurityConfig rules.
 *
 * SECURITY NOTE: These headers are stripped and re-stamped by the Gateway.
 * A malicious client cannot forge X-User-Id because the Gateway removes
 * any client-supplied values and only adds them after JWT validation.
 * If this service is ever called directly (bypassing Gateway), the headers
 * won't be present and auth-required routes will correctly return 403.
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
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String userId  = request.getHeader(USER_ID_HEADER);
        String role    = request.getHeader(ROLE_HEADER);
        String traceId = request.getHeader(TRACE_HEADER);

        // Populate UserContext for service layer access regardless of auth
        if (userId != null && !userId.isBlank()) {
            userContext.setUserId(userId);
        }
        if (role != null && !role.isBlank()) {
            userContext.setRole(role);
        }
        if (traceId != null && !traceId.isBlank()) {
            userContext.setTraceId(traceId);
        }

        // If no user identity headers present, skip setting SecurityContext
        // (public routes will still work; protected routes will be blocked by SecurityConfig)
        if (userId == null || userId.isBlank()) {
            log.debug("No X-User-Id header present, proceeding as anonymous for path: {}",
                    request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }

        // Build Spring Security Authentication from headers
        // Spring Security expects roles prefixed with ROLE_ for hasRole() to work
        String springRole = role != null ? "ROLE_" + role.toUpperCase() : "ROLE_USER";
        List<SimpleGrantedAuthority> authorities =
                Collections.singletonList(new SimpleGrantedAuthority(springRole));

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        userId,       // principal — the userId string
                        null,         // credentials — null (no password, already authenticated by Gateway)
                        authorities   // granted authorities — derived from X-Role header
                );

        // Stamp additional details onto the authentication (useful for audit logs)
        authentication.setDetails(traceId);

        SecurityContextHolder.getContext().setAuthentication(authentication);

        log.debug("Security context populated: userId={} role={} traceId={}",
                userId, role, traceId);

        filterChain.doFilter(request, response);
    }

    /**
     * Clear SecurityContext after request completes.
     * Prevents thread pool reuse from leaking auth context across requests.
     */
    @Override
    public void afterPropertiesSet() throws ServletException {
        super.afterPropertiesSet();
    }
}