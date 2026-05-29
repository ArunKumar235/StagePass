package org.stagepass.apigateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {

    /**
     * KEY RESOLVER — determines what to rate-limit by.
     *
     * Strategy:
     *  1. Use X-User-Id header (stamped by JwtAuthenticationFilter) for authenticated requests
     *  2. Fall back to client IP for unauthenticated routes like /auth/** and GET /events/**
     *
     * This ensures:
     *  - Authenticated users have individual buckets (fair per-user limiting)
     *  - Unauthenticated callers are grouped by IP (prevents registration spam)
     *
     * Named "userKeyResolver" so it can be @Qualifier-injected alongside
     * the ipKeyResolver when needed.
     */
    @Bean
    @Primary
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                return Mono.just("user:" + userId);
            }
            // Fall back to IP address for unauthenticated routes
            String ip = exchange.getRequest().getRemoteAddress() != null
                    ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                    : "unknown";
            return Mono.just("ip:" + ip);
        };
    }

    /**
     * GLOBAL RATE LIMITER — applied to all routes via application.yml default-filters.
     *
     * replenishRate = 20  → tokens added per second (steady-state throughput)
     * burstCapacity  = 40 → max tokens in bucket (absorbs short bursts)
     *
     * A user making 20 req/s continuously is fine.
     * A user making 40 req in 1 second is fine once, then throttled until bucket refills.
     * Exceeding burst → 429 Too Many Requests.
     *
     * Redis stores the bucket state atomically via a Lua script — this is safe
     * even when multiple gateway instances run in parallel (K8s horizontal scaling).
     */
    @Bean
    @Primary
    public RedisRateLimiter globalRateLimiter() {
        // replenishRate=20, burstCapacity=40, requestedTokens=1
        return new RedisRateLimiter(20, 40, 1);
    }

    /**
     * BOOKING RATE LIMITER — applied only to /bookings/** routes.
     *
     * Much stricter than the global limiter.
     * replenishRate = 3  → 3 booking attempts per second
     * burstCapacity  = 5 → max 5 in a burst
     *
     * Why: during a flash sale, thousands of users simultaneously hit POST /bookings.
     * Without this, a single bot/user can exhaust seat locks, leaving real users locked out.
     * This limits each user to 3 attempts/sec — enough for a human, throttles bots.
     */
    @Bean("bookingRateLimiter")
    public RedisRateLimiter bookingRateLimiter() {
        return new RedisRateLimiter(3, 5, 1);
    }

    /**
     * IP KEY RESOLVER — used for unauthenticated routes like /auth/register.
     *
     * Prevents registration flooding from a single IP.
     * Injected by @Qualifier("ipKeyResolver") where needed.
     */
    @Bean("ipKeyResolver")
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            // Check X-Forwarded-For first (for requests behind a proxy/load balancer)
            String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // X-Forwarded-For can be a comma-separated list; take the first (original client)
                return Mono.just("ip:" + forwarded.split(",")[0].trim());
            }
            String ip = exchange.getRequest().getRemoteAddress() != null
                    ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                    : "unknown";
            return Mono.just("ip:" + ip);
        };
    }
}