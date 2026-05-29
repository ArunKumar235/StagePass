package org.stagepass.eventservice.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class CacheConfig {


    /**
     * Cache manager with per-cache TTL configuration.
     *
     * Three caches with different TTLs based on how frequently the data changes:
     *
     * events-list  → 5 min TTL: paginated event listings. High read traffic,
     *                changes only when events are created, updated, or published.
     *
     * event-detail → 10 min TTL: single event detail page. Rarely changes once
     *                published. Evicted explicitly when event is updated.
     *
     * seat-map     → 30 sec TTL: seat availability. Short because seat statuses
     *                change during active booking windows (locks, confirmations).
     *                Long enough to reduce DB load; short enough to feel near-real-time.
     *
     * venue-list   → 1 hour TTL: venue data changes very infrequently.
     *
     * All caches use JSON serialization so cached objects survive service restarts
     * (unlike Java serialization which breaks on class changes).
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisSerializer<Object> jsonSerializer = RedisSerializer.json();

        // Default config: JSON serialization, no null caching
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(jsonSerializer)
                )
                .disableCachingNullValues()        // Never cache null — throw instead
                .prefixCacheNameWith("stagepass:"); // Key prefix: stagepass:events-list::...

        // Per-cache TTL overrides
        Map<String, RedisCacheConfiguration> cacheConfigs = new HashMap<>();

        cacheConfigs.put("events-list",
                defaultConfig.entryTtl(Duration.ofMinutes(5)));

        cacheConfigs.put("event-detail",
                defaultConfig.entryTtl(Duration.ofMinutes(10)));

        cacheConfigs.put("seat-map",
                defaultConfig.entryTtl(Duration.ofSeconds(30)));

        cacheConfigs.put("venue-list",
                defaultConfig.entryTtl(Duration.ofHours(1)));

        cacheConfigs.put("venue-detail",
                defaultConfig.entryTtl(Duration.ofHours(1)));

        cacheConfigs.put("available-count",
                defaultConfig.entryTtl(Duration.ofSeconds(15))); // Very short — changes rapidly

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig.entryTtl(Duration.ofMinutes(5))) // Default TTL if not specified above
                .withInitialCacheConfigurations(cacheConfigs)
                .transactionAware() // Evict cache only if DB transaction commits successfully
                .build();
    }
}
