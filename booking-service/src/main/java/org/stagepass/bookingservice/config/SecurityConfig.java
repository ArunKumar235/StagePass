package org.stagepass.bookingservice.config;

import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.stagepass.bookingservice.security.HeaderAuthFilter;
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
 * No JWT parsing — the Gateway already validated the token and stamped
 * X-User-Id + X-Role headers. HeaderAuthFilter translates these into
 * a Spring Security Authentication for @PreAuthorize to work.
 *
 * All booking endpoints require authentication — there are no public routes
 * in this service (unlike Event Service where GET /events is public).
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
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth

                        // K8s health probes — always open
                        .requestMatchers("/actuator/**").permitAll()

                        // All booking endpoints require authentication
                        .requestMatchers(HttpMethod.POST,   "/bookings").authenticated()
                        .requestMatchers(HttpMethod.GET,    "/bookings/my").authenticated()
                        .requestMatchers(HttpMethod.GET,    "/bookings/{bookingId}").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/bookings/{bookingId}").authenticated()

                        // Admin endpoints — ADMIN role only
                        .requestMatchers("/bookings/admin/**").hasRole("ADMIN")

                        .anyRequest().authenticated()
                )
                .addFilterBefore(headerAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
