package org.stagepass.eventservice.config;

import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.stagepass.eventservice.security.HeaderAuthFilter;
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

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true) // enables @PreAuthorize on service/controller methods
public class SecurityConfig {

    @Autowired
    private HeaderAuthFilter headerAuthFilter;

    /**
     * Security filter chain for the Event Service.
     *
     * Key design decisions:
     * - NO JWT parsing here — the Gateway already validated the token
     * - HeaderAuthFilter translates X-User-Id / X-Role into a Spring Security context
     * - Public GET routes are fully open (event browsing requires no login)
     * - Write operations require ORGANISER or ADMIN role
     * - Session is STATELESS — no server-side session, ever
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth

                        // ── PUBLIC: anyone can browse events and venues ──────────────
                        .requestMatchers(HttpMethod.GET, "/events/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/venues/**").permitAll()

                        // ── ACTUATOR: always open for K8s health probes ──────────────
                        .requestMatchers("/actuator/**").permitAll()

                        // ── ORGANISER: create and manage events ──────────────────────
                        .requestMatchers(HttpMethod.POST, "/events").hasRole("ORGANISER")
                        .requestMatchers(HttpMethod.PUT, "/events/**").hasRole("ORGANISER")
                        .requestMatchers(HttpMethod.PATCH, "/events/*/publish").hasRole("ORGANISER")

                        // ── ADMIN: delete events, manage venues ───────────────────────
                        .requestMatchers(HttpMethod.DELETE, "/events/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/venues").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/venues/**").hasRole("ADMIN")

                        // ── EVERYTHING ELSE: authenticated users only ─────────────────
                        .anyRequest().authenticated()
                )
                // Register our header-based auth filter BEFORE Spring's default auth filter
                .addFilterBefore(headerAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
