package org.stagepass.apigateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtAuthenticationFilterTest {

    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter();

    @Test
    void testIsPublicPath_WhitelistedPaths() {
        // Auth paths should be public
        assertTrue(filter.isPublicPath("/auth/register", HttpMethod.POST));
        assertTrue(filter.isPublicPath("/auth/login", HttpMethod.POST));

        // Actuator endpoints should be public
        assertTrue(filter.isPublicPath("/actuator/health", HttpMethod.GET));

        // OAuth2 paths should be public
        assertTrue(filter.isPublicPath("/oauth2/authorization/google", HttpMethod.GET));

        // Payments webhook path should be public
        assertTrue(filter.isPublicPath("/payments/webhook", HttpMethod.POST));
        assertTrue(filter.isPublicPath("/payments/webhook/stripe", HttpMethod.POST));
    }

    @Test
    void testIsPublicPath_ProtectedPaths() {
        // Normal payment requests should require authentication
        assertFalse(filter.isPublicPath("/payments/charge", HttpMethod.POST));
        assertFalse(filter.isPublicPath("/payments/history", HttpMethod.GET));

        // Users endpoint should require authentication
        assertFalse(filter.isPublicPath("/users/profile", HttpMethod.GET));
        assertFalse(filter.isPublicPath("/users/123", HttpMethod.DELETE));
    }

    @Test
    void testIsPublicPath_EventsEndpoints() {
        // GET /events is public
        assertTrue(filter.isPublicPath("/events", HttpMethod.GET));
        assertTrue(filter.isPublicPath("/events/123", HttpMethod.GET));

        // POST/PUT/DELETE /events are protected
        assertFalse(filter.isPublicPath("/events", HttpMethod.POST));
        assertFalse(filter.isPublicPath("/events/123", HttpMethod.PUT));
        assertFalse(filter.isPublicPath("/events/123", HttpMethod.DELETE));
    }
}
