package org.stagepass.eventservice.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * USER CONTEXT
 *
 * A request-scoped bean that holds the identity of the user making
 * the current HTTP request. Populated by HeaderAuthFilter at the
 * start of each request and discarded at the end.
 *
 * @RequestScope creates a new instance per HTTP request — completely
 * thread-safe with no risk of leaking one user's context into another.
 *
 * Used by:
 *   - AuditConfig.auditorProvider() → supplies userId for @CreatedBy / @LastModifiedBy
 *   - EventService → reads userId to set organizerId on new events
 *   - SeatService → reads userId for audit trail on seat map generation
 *
 * Alternative approach: read from SecurityContextHolder directly in services.
 * UserContext is cleaner because it avoids coupling service logic to Spring
 * Security internals and makes unit testing easier (just set fields on the mock).
 */
@Setter
@Getter
@Component
@RequestScope
public class UserContext {

    private String userId;
    private String role;
    private String traceId;

    public boolean isAuthenticated() {
        return userId != null && !userId.isBlank();
    }

    public boolean hasRole(String roleName) {
        return roleName != null && roleName.equalsIgnoreCase(this.role);
    }
}
