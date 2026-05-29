package org.stagepass.paymentservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * IDEMPOTENCY CONFIG
 *
 * Configures Redis for idempotency key storage.
 *
 * WHY IDEMPOTENCY MATTERS HERE:
 * The Booking Service calls POST /payments/charge via Feign with retry logic.
 * If the charge succeeds but the HTTP response is lost (network hiccup), Feign
 * retries — and without idempotency, the user gets charged twice.
 *
 * HOW IT WORKS:
 * Key: "payment:idempotency:{bookingId}" — for charges
 * "refund:idempotency:{paymentId}" — for refunds
 * Value: JSON-serialised response (ChargeResponse or RefundResponse)
 * TTL: 1 hour (configurable via stagepass.payment.idempotency-ttl-hours)
 *
 * On every charge/refund:
 * 1. Check Redis for existing result with this key
 * 2. If found → return cached result immediately (no gateway call)
 * 3. If not found → call gateway → store result → return result
 *
 * This guarantees: same bookingId = same payment, no matter how many times
 * called.
 *
 * VALUE SERIALISATION:
 * Uses JacksonJsonRedisSerializer (JSON) for values — unlike
 * SeatLockService which uses StringSerializer. We need JSON here because
 * ChargeResponse and RefundResponse are complex objects, not plain strings.
 *
 * SEPARATE FROM BOOKING SERVICE'S REDIS:
 * Same Redis instance, but different key namespaces.
 * Payment keys: "payment:idempotency:..."
 * Booking seat lock keys: "seat:lock:..."
 * No collision possible.
 */
@Configuration
public class IdempotencyConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(host);
        config.setPort(port);
        if (password != null && !password.isBlank()) {
            config.setPassword(password);
        }
        return new LettuceConnectionFactory(config);
    }

    /**
     * RedisTemplate<String, Object> for idempotency store.
     *
     * Key serializer: StringRedisSerializer — human-readable key names
     * Value serializer: JacksonJsonRedisSerializer — serialises
     * ChargeResponse/RefundResponse objects to JSON
     *
     * The JSON serializer embeds the class type in the JSON so deserialization
     * knows what object to reconstruct. This is why the stored JSON includes
     * "@class" field — don't strip it.
     */
    @Bean(name = "idempotencyRedisTemplate") // Qualifier for injection into IdempotencyService
    public RedisTemplate<String, Object> idempotencyRedisTemplate(
            RedisConnectionFactory factory) {

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // String keys for human-readable cache inspection via redis-cli
        template.setKeySerializer(new StringRedisSerializer());

        // JSON value serialisation for complex response objects
        template.setValueSerializer(new JacksonJsonRedisSerializer<>(Object.class));

        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new JacksonJsonRedisSerializer<>(Object.class));

        template.afterPropertiesSet();
        return template;
    }
}