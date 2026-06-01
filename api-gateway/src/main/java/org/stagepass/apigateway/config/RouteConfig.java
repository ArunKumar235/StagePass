package org.stagepass.apigateway.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.stagepass.apigateway.filter.JwtAuthenticationFilter;

@Configuration
public class RouteConfig {

        @Autowired
        private JwtAuthenticationFilter jwtAuthFilter;

        @Autowired
        private RateLimiterConfig rateLimiterConfig;

        /**
         * Programmatic route definitions.
         *
         * Most routes are also declarable in application.yml — we use Java config here
         * for routes that need dynamic filters or conditional logic (e.g. the stricter
         * rate limiter on /bookings/**).
         *
         * Route order matters: Spring Cloud Gateway evaluates predicates top-down and
         * stops at the first match — put more specific routes above generic ones.
         */
        @Bean
        public RouteLocator stagePassRoutes(RouteLocatorBuilder builder) {
                return builder.routes()

                                // ── USER SERVICE ─────────────────────────────────────────────
                                // Public routes: no JWT filter applied
                                .route("user-service-public", r -> r
                                                .path("/auth/**", "/oauth2/**", "/login/**")
                                                .filters(f -> f
                                                                .circuitBreaker(config -> config
                                                                                .setName("userServiceCB")))
                                                .uri("lb://user-service"))

                                // Protected user profile routes
                                .route("user-service-protected", r -> r
                                                .path("/users/**")
                                                .filters(f -> f
                                                                .filter(jwtAuthFilter.apply(
                                                                                new JwtAuthenticationFilter.Config()))
                                                                .circuitBreaker(config -> config
                                                                                .setName("userServiceCB")))
                                                .uri("lb://user-service"))

                                // ── EVENT SERVICE ─────────────────────────────────────────────
                                // GET /events/** is public (browse events without logging in)
                                .route("event-service-public", r -> r
                                                .path("/events/**", "/venues/**")
                                                .and()
                                                .method(HttpMethod.GET)
                                                .filters(f -> f
                                                                .circuitBreaker(config -> config
                                                                                .setName("eventServiceCB")))
                                                .uri("lb://event-service"))

                                // POST/PUT/DELETE /events/** requires ORGANISER role — JWT filter
                                // enforces authentication; role check happens inside event-service
                                .route("event-service-protected", r -> r
                                                .path("/events/**", "/venues/**")
                                                .and()
                                                .method(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE,
                                                                HttpMethod.PATCH)
                                                .filters(f -> f
                                                                .filter(jwtAuthFilter.apply(
                                                                                new JwtAuthenticationFilter.Config()))
                                                                .circuitBreaker(config -> config
                                                                                .setName("eventServiceCB")))
                                                .uri("lb://event-service"))

                                // ── BOOKING SERVICE ───────────────────────────────────────────
                                // Strict rate limiter: 3 requests/min per user to prevent seat hogging
                                .route("booking-service", r -> r
                                                .path("/bookings/**")
                                                .filters(f -> f
                                                                .filter(jwtAuthFilter.apply(
                                                                                new JwtAuthenticationFilter.Config()))
                                                                .requestRateLimiter(config -> config
                                                                                .setRateLimiter(rateLimiterConfig
                                                                                                .bookingRateLimiter())
                                                                                .setKeyResolver(rateLimiterConfig
                                                                                                .userKeyResolver()))
                                                                // BENCHMARKING: Circuit breaker disabled.
                                                                // The bookingServiceCB was amplifying genuine payment
                                                                // failures into a cascade of UUID-format 500s from
                                                                // the gateway, making it impossible to measure the
                                                                // real failure distribution. Re-enable for production.
                                                                // .circuitBreaker(config -> config
                                                                //         .setName("bookingServiceCB"))
                                                                )
                                                .uri("lb://booking-service"))

                                // ── PAYMENT SERVICE ───────────────────────────────────────────
                                .route("payment-service", r -> r
                                                .path("/payments/**")
                                                .filters(f -> f
                                                                .filter(jwtAuthFilter.apply(
                                                                                new JwtAuthenticationFilter.Config()))
                                                                .circuitBreaker(config -> config
                                                                                .setName("paymentServiceCB")))
                                                .uri("lb://payment-service"))


                                // ── NOTIFICATION SERVICE ──────────────────────────────────────
                                // Internal service — only reachable through gateway by admins
                                .route("notification-service", r -> r
                                                .path("/notifications/**")
                                                .filters(f -> f
                                                                .filter(jwtAuthFilter.apply(
                                                                                new JwtAuthenticationFilter.Config()))
                                                                .circuitBreaker(config -> config
                                                                                .setName("notificationServiceCB")))
                                                .uri("lb://notification-service"))

                                .build();
        }
}
