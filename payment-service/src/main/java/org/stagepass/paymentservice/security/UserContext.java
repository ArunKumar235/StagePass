package org.stagepass.paymentservice.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * USER CONTEXT
 *
 * Request-scoped holder for the current user's identity.
 * Populated by HeaderAuthFilter at the start of each HTTP request.
 * A fresh instance per request — completely thread-safe.
 *
 * Used by:
 *   PaymentService      → to set userID on PaymentRecord for audit trail.
 *
 * Cleaner than reading SecurityContextHolder in service classes —
 * avoids coupling to Spring Security internals and simplifies unit tests
 * (just inject a plain UserContext with fields set directly).
 */
@Getter
@Setter
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