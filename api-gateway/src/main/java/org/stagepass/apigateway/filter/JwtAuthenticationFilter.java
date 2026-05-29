package org.stagepass.apigateway.filter;

import io.jsonwebtoken.Claims;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.stagepass.apigateway.security.JwtTokenValidator;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * JWT AUTHENTICATION FILTER
 *
 * Runs before routing on every request. Responsibilities:
 * 1. Skip JWT check for whitelisted public paths
 * 2. Extract and validate the Bearer token from Authorization header
 * 3. Short-circuit with 401 if token is missing or invalid
 * 4. Attach userId and role to the exchange as mutable request attributes
 * (RequestHeaderEnrichmentFilter reads these and stamps them as headers)
 *
 * Built as a GatewayFilterFactory (not GlobalFilter) so it can be applied
 * per-route in RouteConfig.java, giving us fine-grained control over which
 * routes require auth. GlobalFilter would apply to ALL routes with no way to
 * opt out.
 */
@Component
public class JwtAuthenticationFilter extends AbstractGatewayFilterFactory<JwtAuthenticationFilter.Config> {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String USER_ID_ATTR = "X-User-Id";
    private static final String ROLE_ATTR = "X-Role";

    /**
     * Paths that bypass JWT validation entirely.
     * /auth/** → register and login (credential flow)
     * /oauth2/** → OAuth2 initiation and callback (Spring Security handles
     * internally)
     * GET /events → public event browsing (no login required)
     */
    private static final List<String> PUBLIC_PATHS = List.of(
            "/auth/register",
            "/auth/login",
            "/oauth2",
            "/login",
            "/actuator", // health probes from K8s — never require auth
            "/payments/webhook",
            "/payments/pay",
            "/payments/pay/",
            "/payments/css/",
            "/payments/js/");

    @Autowired
    private JwtTokenValidator jwtTokenValidator;

    public JwtAuthenticationFilter() {
        super(Config.class);
    }

    @NotNull
    @Override
    public GatewayFilter apply(@NotNull Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            String path = request.getURI().getPath();
            HttpMethod method = request.getMethod();

            // ── STEP 1: Check if this path is whitelisted ─────────────────
            // Only skip JWT validation for explicitly public routes (e.g. GET /events/**)
            if (isPublicPath(path, method)) {
                log.debug("Public path, skipping JWT check: {}", path);
                return chain.filter(exchange);
            }

            // ── STEP 2: Extract Authorization header ──────────────────────
            String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

            if (authHeader == null || authHeader.isBlank()) {
                log.warn("Missing Authorization header for path: {}", path);

                return rejectWith(exchange, HttpStatus.UNAUTHORIZED, "Missing Authorization header");
            }

            if (!authHeader.startsWith(BEARER_PREFIX)) {
                log.warn("Authorization header does not start with Bearer for path: {}", path);
                return rejectWith(exchange, HttpStatus.UNAUTHORIZED,
                        "Invalid Authorization format. Expected: Bearer <token>");
            }

            String token = authHeader.substring(BEARER_PREFIX.length()).trim();

            // ── STEP 3: Validate token ────────────────────────────────────
            if (!jwtTokenValidator.validate(token)) {
                log.warn("Invalid or expired JWT for path: {}", path);
                return rejectWith(exchange, HttpStatus.UNAUTHORIZED, "Token is invalid or expired");
            }

            // ── STEP 4: Extract claims and attach to exchange ─────────────
            try {
                Claims claims = jwtTokenValidator.extractClaims(token);
                String userId = claims.get("userId", String.class);
                String role = claims.get("role", String.class);

                if (userId == null || userId.isBlank()) {
                    log.warn("JWT missing userId claim for path: {}", path);
                    return rejectWith(exchange, HttpStatus.UNAUTHORIZED, "Token missing required claims");
                }

                // Attach to exchange attributes — RequestHeaderEnrichmentFilter
                // reads these and stamps them as X-User-Id / X-Role headers
                ServerHttpRequest mutatedRequest = exchange.getRequest()
                        .mutate()
                        .header(USER_ID_ATTR, userId)
                        .header(ROLE_ATTR, role != null ? role : "USER")
                        // Strip the raw Authorization header — downstream services
                        // should never see the raw JWT; they trust X-User-Id instead
                        .headers(h -> h.remove(HttpHeaders.AUTHORIZATION))
                        .build();

                log.debug("JWT validated for userId={} role={} path={}", userId, role, path);

                return chain.filter(exchange.mutate().request(mutatedRequest).build());

            } catch (Exception e) {
                log.error("Error extracting JWT claims for path: {}", path, e);
                return rejectWith(exchange, HttpStatus.UNAUTHORIZED, "Failed to process token");
            }
        };
    }

    /**
     * Checks if the request path starts with any public path prefix.
     * Also allows all GET requests to /events/** for public browsing.
     */
    boolean isPublicPath(String path, HttpMethod method) {
        for (String publicPath : PUBLIC_PATHS) {
            if (path.startsWith(publicPath)) {
                return true;
            }
        }

        // Allow GET /events/** , GET /venues/** without auth (public browsing)
        return method == HttpMethod.GET && (path.startsWith("/events") || path.startsWith("/venues"));
    }

    /**
     * Writes a JSON 401 response and completes the exchange without calling the
     * next filter.
     * This short-circuits the entire filter chain — no downstream service is ever
     * called.
     */
    private Mono<Void> rejectWith(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = String.format(
                "{\"status\":%d,\"error\":\"%s\",\"message\":\"%s\"}",
                status.value(), status.getReasonPhrase(), message);

        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(body.getBytes())));
    }

    /**
     * Config class for this filter factory.
     * Empty for now — add fields here if you need per-route filter configuration,
     * e.g. required roles: new Config().setRequiredRole("ORGANISER")
     */
    public static class Config {
        // Extend with per-route config if needed
        // e.g.: private String requiredRole;
    }
}
