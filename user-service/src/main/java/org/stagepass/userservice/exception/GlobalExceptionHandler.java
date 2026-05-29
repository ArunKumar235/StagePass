package org.stagepass.userservice.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.stagepass.userservice.dto.ErrorResponse;
import org.stagepass.userservice.security.InvalidTokenException;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(UserAlreadyExistsException.class)
	public ResponseEntity<ErrorResponse> handleUserAlreadyExists(UserAlreadyExistsException ex) {
		log.warn("User already exists: {}", ex.getMessage());
		ErrorResponse errorResponse = ErrorResponse.of(
				HttpStatus.CONFLICT.value(),
				"CONFLICT",
				ex.getMessage());
		return new ResponseEntity<>(errorResponse, HttpStatus.CONFLICT);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
		log.warn("Validation failed");
		Map<String, java.util.List<String>> fieldErrors = ex.getBindingResult()
				.getFieldErrors()
				.stream()
				.collect(Collectors.groupingBy(
						FieldError::getField,
						Collectors.mapping(error -> error.getDefaultMessage() != null ? error.getDefaultMessage()
								: "Invalid value", Collectors.toList())));

		ErrorResponse errorResponse = ErrorResponse.of(
				HttpStatus.BAD_REQUEST.value(),
				"VALIDATION_ERROR",
				"Request validation failed",
				fieldErrors);
		return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
	}

	@ExceptionHandler(BadCredentialsException.class)
	public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex) {
		log.warn("Bad credentials attempt");
		ErrorResponse errorResponse = ErrorResponse.of(
				HttpStatus.UNAUTHORIZED.value(),
				"UNAUTHORIZED",
				"Invalid email or password");
		return new ResponseEntity<>(errorResponse, HttpStatus.UNAUTHORIZED);
	}

	@ExceptionHandler(InvalidTokenException.class)
	public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex) {
		log.warn("Invalid or expired token: {}", ex.getMessage());
		ErrorResponse errorResponse = ErrorResponse.of(
				HttpStatus.UNAUTHORIZED.value(),
				"INVALID_TOKEN",
				"Invalid or expired token");
		return new ResponseEntity<>(errorResponse, HttpStatus.UNAUTHORIZED);
	}

	@ExceptionHandler(OAuth2AuthenticationException.class)
	public ResponseEntity<ErrorResponse> handleOAuth2AuthenticationException(OAuth2AuthenticationException ex) {
		log.warn("OAuth2 authentication failed: {}", ex.getMessage());
		ErrorResponse errorResponse = ErrorResponse.of(
				HttpStatus.UNAUTHORIZED.value(),
				ex.getError() != null ? ex.getError().getErrorCode() : "OAUTH2_ERROR",
				ex.getError() != null ? ex.getError().getDescription() : ex.getMessage());
		return new ResponseEntity<>(errorResponse, HttpStatus.UNAUTHORIZED);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
		log.debug("Resource not found: {}", ex.getMessage());
		ErrorResponse errorResponse = ErrorResponse.of(
				HttpStatus.NOT_FOUND.value(),
				"NOT_FOUND",
				ex.getMessage());
		return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
		log.error("Unexpected error occurred", ex);
		ErrorResponse errorResponse = ErrorResponse.of(
				HttpStatus.INTERNAL_SERVER_ERROR.value(),
				"INTERNAL_SERVER_ERROR",
				"An unexpected error occurred. Please try again later.");
		return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
	}
}
