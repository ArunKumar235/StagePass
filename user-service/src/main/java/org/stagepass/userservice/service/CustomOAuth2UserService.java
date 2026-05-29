package org.stagepass.userservice.service;

import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.stagepass.userservice.entity.AuthProvider;
import org.stagepass.userservice.entity.Role;
import org.stagepass.userservice.entity.User;
import org.stagepass.userservice.repository.UserRepository;
import org.stagepass.userservice.security.CustomOAuth2User;
import org.stagepass.userservice.exception.PasswordLoginRequiredException;
import org.stagepass.userservice.kafka.UserEventPublisher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@NullMarked
@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

	private static final Logger log = org.slf4j.LoggerFactory.getLogger(CustomOAuth2UserService.class);

	private final UserRepository userRepository;
	private final UserEventPublisher userEventPublisher;

	public CustomOAuth2UserService(UserRepository userRepository,
			UserEventPublisher userEventPublisher) {
		this.userRepository = userRepository;
		this.userEventPublisher = userEventPublisher;
	}

	@Override
	public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
		OAuth2User oauth2User = super.loadUser(userRequest);
		Map<String, Object> attributes = new HashMap<>(oauth2User.getAttributes());

		String registrationId = userRequest.getClientRegistration().getRegistrationId();
		AuthProvider authProvider = resolveAuthProvider(registrationId);
		String email = firstNonBlank(attributes, "email");
		String providerId = firstNonBlank(attributes, "sub", "id");
		String name = firstNonBlank(attributes, "name", "login", "given_name");

		if (email == null || email.isBlank()) {
			throw new OAuth2AuthenticationException(
					new OAuth2Error("missing_email", "OAuth2 provider did not return an email address", null));
		}

		User user = userRepository.findByEmail(email)
				.map(existing -> applyExistingUserRules(existing, authProvider, providerId, name, email))
				.orElseGet(() -> createNewOAuthUser(email, providerId, name, authProvider));

		boolean isNewUser = (user.getId() == null);

		user = userRepository.save(user);

		if (isNewUser) {
			try {
				userEventPublisher.publishUserRegistered(user);
				log.info("Published user-registered event for new OAuth user: email={}", email);
			} catch (Exception e) {
				log.error("Failed to publish registration event for OAuth user: email={}", email, e);
			}
		}

		Map<String, Object> principalAttributes = new HashMap<>(attributes);
		principalAttributes.put("userId", user.getId() != null ? user.getId().toString() : null);
		principalAttributes.put("email", user.getEmail());
		principalAttributes.put("name", user.getUsername());
		principalAttributes.put("username", user.getUsername());
		principalAttributes.put("role", user.getRole() != null ? user.getRole().name() : Role.USER.name());
		principalAttributes.put("authProvider",
				user.getAuthProvider() != null ? user.getAuthProvider().name() : authProvider.name());
		principalAttributes.put("providerId", user.getProviderId());

		return new CustomOAuth2User(user, principalAttributes, authoritiesFor(user));
	}

	private User applyExistingUserRules(User existing, AuthProvider requestedProvider, String providerId, String name,
			String email) {
		if (existing.getAuthProvider() == AuthProvider.LOCAL) {
			throw new PasswordLoginRequiredException(email);
		}

		if (existing.getAuthProvider() == null) {
			existing.setAuthProvider(requestedProvider);
		}
		if (providerId != null && !providerId.isBlank() && !Objects.equals(existing.getProviderId(), providerId)) {
			existing.setProviderId(providerId);
		}
		// Only initialize or update the username if it is currently blank or null.
		// This ensures any custom local username update made by the user is preserved
		// and not overwritten by the OAuth provider's name on subsequent logins.
		if (existing.getUsername() == null || existing.getUsername().isBlank()) {
			if (name != null && !name.isBlank()) {
				existing.setUsername(name);
			} else {
				existing.setUsername(email);
			}
		}
		if (existing.getRole() == null) {
			existing.setRole(Role.USER);
		}

		return existing;
	}

	private User createNewOAuthUser(String email, String providerId, String name, AuthProvider authProvider) {
		return User.builder()
				.email(email)
				.username(name != null && !name.isBlank() ? name : email)
				.passwordHash(null)
				.role(Role.USER)
				.authProvider(authProvider)
				.providerId(providerId)
				.build();
	}

	private AuthProvider resolveAuthProvider(String registrationId) {
		if (registrationId == null) {
			return AuthProvider.GOOGLE;
		}
		return switch (registrationId.toLowerCase()) {
			case "github" -> AuthProvider.GITHUB;
			default -> AuthProvider.GOOGLE;
		};
	}

	private List<GrantedAuthority> authoritiesFor(User user) {
		Role role = user.getRole() != null ? user.getRole() : Role.USER;
		return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	private String firstNonBlank(Map<String, Object> attributes, String... keys) {
		for (String key : keys) {
			Object value = attributes.get(key);
			if (value != null) {
				String s = String.valueOf(value).trim();
				if (!s.isBlank()) {
					return s;
				}
			}
		}
		return null;
	}
}
