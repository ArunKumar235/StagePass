package org.stagepass.userservice.exception;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

public class PasswordLoginRequiredException extends OAuth2AuthenticationException {

    private static final String ERROR_CODE = "password_login_required";

    public PasswordLoginRequiredException(String email) {
        super(new OAuth2Error(
                ERROR_CODE,
                "Account already exists for %s. Please log in with your password instead.".formatted(email),
                null
        ));
    }
}

