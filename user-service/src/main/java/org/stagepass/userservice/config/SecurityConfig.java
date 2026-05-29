package org.stagepass.userservice.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.stagepass.userservice.security.CustomUserDetailsService;
import org.stagepass.userservice.security.OAuth2SuccessHandler;
import org.stagepass.userservice.service.CustomOAuth2UserService;
import org.stagepass.userservice.service.UserService;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private final CustomOAuth2UserService customOAuth2UserService;
	private final OAuth2SuccessHandler oAuth2SuccessHandler;
	private final CustomUserDetailsService customUserDetailsService;
	private final PasswordEncoder passwordEncoder;
	private final String callbackPath;

	public SecurityConfig(CustomOAuth2UserService customOAuth2UserService,
			OAuth2SuccessHandler oAuth2SuccessHandler,
			CustomUserDetailsService customUserDetailsService,
			PasswordEncoder passwordEncoder,
			@Value("${app.oauth2.callback-path}") String callbackPath) {
		this.customOAuth2UserService = customOAuth2UserService;
		this.oAuth2SuccessHandler = oAuth2SuccessHandler;
		this.customUserDetailsService = customUserDetailsService;
		this.passwordEncoder = passwordEncoder;
		this.callbackPath = callbackPath;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, UserService userService) {
		return http
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.anyRequest().permitAll() // All routes are public - API gateway handles token validation and
													// user context injection. This service just needs to trust the
													// gateway and focus on business logic.
				)
				.authenticationProvider(authenticationProvider())
				.oauth2Login(oauth2 -> oauth2
						.userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
						.successHandler(oAuth2SuccessHandler)
						.failureHandler((request, response, exception) -> {
							String errorMessage = "OAuth2 authentication failed";
							if (exception instanceof OAuth2AuthenticationException oauthException) {
								errorMessage = oauthException.getError().getDescription();
							}
							String redirectUrl = UriComponentsBuilder.fromUriString(callbackPath)
									.queryParam("error", errorMessage)
									.build()
									.encode()
									.toUriString();
							new DefaultRedirectStrategy().sendRedirect(request, response, redirectUrl);
						}))
				.logout(logout -> logout
						.logoutUrl("/auth/logout")
						.addLogoutHandler((req, res, auth) -> {
							userService.logout(res);
						})
						.logoutSuccessHandler((request, response, authentication) -> {
							response.setStatus(HttpServletResponse.SC_NO_CONTENT);
						}))
				.build();
	}

	@Bean
	public AuthenticationProvider authenticationProvider() {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(customUserDetailsService);
		provider.setPasswordEncoder(passwordEncoder);
		return provider;
	}
}
