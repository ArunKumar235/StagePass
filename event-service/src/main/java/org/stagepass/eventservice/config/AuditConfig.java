package org.stagepass.eventservice.config;

import org.stagepass.eventservice.security.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;
import java.util.UUID;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class AuditConfig {

    @Autowired
    private UserContext userContext;

    /**
     * Provides the current user ID to JPA auditing.
     *
     * Spring Data JPA calls this whenever an entity with @CreatedBy or
     * @LastModifiedBy is saved or updated. The userId is read from
     * UserContext — a request-scoped bean populated by HeaderAuthFilter.
     *
     * @CreatedBy  → set once on INSERT, never updated
     * @LastModifiedBy → updated on every UPDATE
     *
     * If no user is in context (e.g. during a Kafka-driven update),
     * returns Optional.empty() and JPA leaves the field unchanged.
     */
    @Bean
    public AuditorAware<UUID> auditorProvider() {
        return () -> {
            String userId = userContext.getUserId();
            if (userId == null || userId.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(UUID.fromString(userId));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        };
    }
}
