package org.stagepass.paymentservice.config;

import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.stagepass.paymentservice.security.HeaderAuthFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * SECURITY CONFIG
 *
 * The Payment Service has two distinct caller types — each with different
 * authentication mechanisms:
 *
 * 1. INTERNAL (Booking Service via API Gateway)
 * Arrives with X-User-Id + X-Role headers stamped by the Gateway.
 * HeaderAuthFilter translates these into a Spring Security context.
 * Routes: POST /payments/charge, POST /payments/refund
 *
 * 2. EXTERNAL (Razorpay Webhooks)
 * Arrives directly from Razorpay — NOT through the API Gateway.
 * No X-User-Id header. Security is via HMAC-SHA256 signature verification
 * inside WebhookController (handled at the application layer, not Spring
 * Security).
 * Route: POST /payments/webhook — must be PERMITTED without header auth.
 *
 * Failure to permit the webhook route would cause Razorpay to get 401s,
 * stop delivering events, and StagePass would miss payment confirmations.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Autowired
    private HeaderAuthFilter headerAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session
                    .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    // Razorpay webhooks — bypass header auth (verified by HMAC in
                    // WebhookController)
                    .requestMatchers(HttpMethod.POST, "/payments/webhook").permitAll()

                    // K8s health probes
                    .requestMatchers("/actuator/**").permitAll()

                    // Internal/authenticated routes
                    .requestMatchers(HttpMethod.GET, "/payments/pay", "/payments/pay/", "/payments/pay.html", "/payments/index.html", "/payments/css/**", "/payments/js/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/payments/charge").authenticated()
                    .requestMatchers(HttpMethod.POST, "/payments/refund").authenticated()

                    // Admin payment lookup
                    .requestMatchers(HttpMethod.GET, "/payments/admin/**").hasRole("ADMIN")

                    .anyRequest().authenticated())
            // Register HeaderAuthFilter — skips webhook path automatically
            // (no X-User-Id header present → filter does nothing → permitAll() takes
            // effect)
            .addFilterBefore(headerAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}