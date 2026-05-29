package org.stagepass.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    /**
     * Allowed frontend origin — injected from application.yml.
     * In dev: http://localhost:3000
     * In prod: https://stagepass.app
     *
     * Never hardcode "*" in production. It disables credentials (cookies) support
     * and opens the API to cross-origin abuse.
     */
    @Value("${stagepass.cors.allowed-origin}")
    private String allowedOrigin;

    /**
     * CORS filter for the entire gateway.
     *
     * Configured here — NOT in individual services. The gateway intercepts every
     * request before it hits a service, so this single filter is the only CORS
     * configuration needed in the entire StagePass system.
     *
     * What CORS does here:
     *  - Responds to browser preflight OPTIONS requests with the correct headers
     *  - Tells the browser which origins, methods, and headers are allowed
     *  - Enables credentials so HttpOnly refresh token cookies work cross-origin
     *
     * Without this, your React/Vue frontend will get a CORS error on every API call
     * even if the backend returns the correct response.
     */
    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // Only allow your frontend domain — no wildcards in production
        config.setAllowedOrigins(List.of(allowedOrigin));

        // Allow all standard HTTP methods
        config.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        ));

        // Allow these request headers from the browser
        // Include "Cookie" so browsers are allowed to send cookies on CORS requests
        config.setAllowedHeaders(List.of(
                "Authorization",        // JWT Bearer token
                "Content-Type",         // application/json
                "X-Requested-With",     // AJAX identifier
                "Accept",
                "Origin",
                "Cookie",               // allow browser to send cookies
                "Access-Control-Request-Method",
                "Access-Control-Request-Headers"
        ));

        // Expose these response headers to the browser JS code
        // (by default, browsers can only read a small set of headers)
        // Expose "Set-Cookie" so cookies set by downstream services are visible
        // to the browser's networking layer (required for cross-site cookies).
        config.setExposedHeaders(List.of(
                "X-Trace-Id",           // so frontend can include in bug reports
                "X-RateLimit-Remaining",// optional: show user their remaining quota
                "Set-Cookie"
        ));

        // IMPORTANT: must be true if using HttpOnly cookies for refresh tokens
        // Cannot be combined with allowedOrigins("*")
        config.setAllowCredentials(true);

        // Cache preflight response for 1 hour — reduces OPTIONS request overhead
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Apply CORS config to every path
        source.registerCorsConfiguration("/**", config);

        return new CorsWebFilter(source);
    }
}
