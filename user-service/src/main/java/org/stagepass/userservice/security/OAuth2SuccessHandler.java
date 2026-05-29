package org.stagepass.userservice.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.stagepass.userservice.entity.User;

import java.io.IOException;

@NullMarked
@Component
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

	private final JwtTokenProvider jwtTokenProvider;
	private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();
	private final String callbackPath;

	public OAuth2SuccessHandler(JwtTokenProvider jwtTokenProvider,
								@Value("${app.oauth2.callback-path:/oauth2/callback}") String callbackPath) {
		this.jwtTokenProvider = jwtTokenProvider;
		this.callbackPath = callbackPath;
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request,
										HttpServletResponse response,
										Authentication authentication) throws IOException {
		User user = extractUser(authentication);
		String token = jwtTokenProvider.generateToken(user);

		String redirectUrl = UriComponentsBuilder.fromUriString(callbackPath)
				.queryParam("token", token)
				.build()
				.encode()
				.toUriString();

		redirectStrategy.sendRedirect(request, response, redirectUrl);
	}

	private User extractUser(Authentication authentication) {
		Object principal = authentication.getPrincipal();
		if (principal instanceof CustomOAuth2User customOAuth2User) {
			return customOAuth2User.getUser();
		}

		throw new IllegalStateException("Unexpected OAuth2 principal type: " + (principal == null ? "null" : principal.getClass().getName()));
	}
}
