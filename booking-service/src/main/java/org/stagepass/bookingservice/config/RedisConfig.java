package org.stagepass.bookingservice.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * REDIS CONFIG
 *
 * Configures RedisTemplate<String, String> used exclusively by SeatLockService
 * for distributed seat locking.
 *
 * KEY DECISIONS:
 *
 * 1. RedisTemplate NOT @Cacheable
 *    Seat locking is a distributed mutex, not caching.
 *    We need direct control over NX (set if not exists) and EX (TTL).
 *    setIfAbsent(key, value, ttl) maps directly to Redis SET NX EX.
 *
 * 2. StringRedisSerializer for both key and value
 *    Keys:   "seat:lock:{seatId}"
 *    Values: "{userId}"
 *    Plain strings — no JSON overhead, human-readable in redis-cli.
 *
 * 3. Connection pooling
 *    Flash sales cause concurrent lock attempts from many threads.
 *    Pool keeps connections warm and limits connection churn.
 *
 * 4. Fail fast timeouts
 *    2s connect, 1s command — a hung Redis call blocks a booking thread.
 *    Better to fail immediately with 503 than to queue up indefinitely.
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration();
        server.setHostName(host);
        server.setPort(port);
        if (password != null && !password.isBlank()) {
            server.setPassword(password);
        }

        // Socket timeout — fail fast
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .build())
                .autoReconnect(true)
                .build();

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .clientOptions(clientOptions)
                .commandTimeout(Duration.ofSeconds(1))
                .build();

        return new LettuceConnectionFactory(server, clientConfig);
    }

    /**
     * RedisTemplate<String, String> for seat lock operations.
     *
     * Lock key:   "seat:lock:{seatId}"
     * Lock value: "{userId}" — who holds the lock
     *
     * SeatLockService uses:
     *   setIfAbsent(key, userId, 600s) → SET key userId NX EX 600
     *   get(key)                        → GET key  (who holds it)
     *   execute(luaScript)              → atomic check-and-delete on release
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer s = new StringRedisSerializer();
        template.setKeySerializer(s);
        template.setValueSerializer(s);
        template.setHashKeySerializer(s);
        template.setHashValueSerializer(s);
        template.afterPropertiesSet();

        return template;
    }
}