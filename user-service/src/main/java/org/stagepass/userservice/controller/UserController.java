package org.stagepass.userservice.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.stagepass.userservice.dto.AuthResponse;
import org.stagepass.userservice.dto.UpdateUserProfileRequest;
import org.stagepass.userservice.dto.UserProfileDto;
import org.stagepass.userservice.service.UserService;

import java.util.UUID;

@RestController
@RequestMapping("/users")
public class UserController {

	private final UserService userService;

	@Autowired
	public UserController(UserService userService) {
		this.userService = userService;
	}

	@GetMapping("/me")
	public ResponseEntity<UserProfileDto> me(@RequestHeader("X-User-Id") UUID userId) {
		return ResponseEntity.ok(userService.getUserById(userId));
	}

	@GetMapping("/{userId}")
	public ResponseEntity<UserProfileDto> getUserById(@PathVariable("userId") UUID userId) {
		return ResponseEntity.ok(userService.getUserById(userId));
	}

	@PutMapping("/me")
	public ResponseEntity<UserProfileDto> updateMe(@RequestHeader("X-User-Id") UUID userId,
			@RequestBody @Valid UpdateUserProfileRequest request) {
		return ResponseEntity.ok(userService.updateCurrentUser(userId, request));
	}

	// Testing Command (run on bash)
	//
	// curl.exe -i -X POST "http://localhost:8080/users/refresh" \
	// -H "Authorization: Bearer
	// eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIyOGE1OWZkOC1mYzAwLTRjZGItOGYwNi1lMTE0Yjk4MTk5ZTQiLCJ1c2VySWQiOiIyOGE1OWZkOC1mYzAwLTRjZGItOGYwNi1lMTE0Yjk4MTk5ZTQiLCJyb2xlIjoiVVNFUiIsImVtYWlsIjoibWFydW5rdW1hcjAyMDNAZ21haWwuY29tIiwiaWF0IjoxNzc5Njk5NTIxLCJleHAiOjE3Nzk3MDMxMjF9.BsCFtFGCp7iq_uo5NQQc8gBaQD5nr8tqTk70MqQk2PY"
	// \
	// -H "Cookie:
	// refresh_token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIyOGE1OWZkOC1mYzAwLTRjZGItOGYwNi1lMTE0Yjk4MTk5ZTQiLCJ1c2VySWQiOiIyOGE1OWZkOC1mYzAwLTRjZGItOGYwNi1lMTE0Yjk4MTk5ZTQiLCJpYXQiOjE3Nzk2OTk1MjEsImV4cCI6MTc4MDMwNDMyMX0.NPx0IFH2HQLUz1gwR3zB9ktcO9Fq1KyLgxJfR13ugfA"
	// \
	// -H "Content-Type: application/json" \
	// --data "{}"

	@PostMapping("/refresh")
	public ResponseEntity<AuthResponse> refresh(
			@CookieValue(name = "refresh_token", required = false) String refreshToken,
			HttpServletResponse response) {

		if (refreshToken == null || refreshToken.isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token is missing");
		}

		return ResponseEntity
				.status(HttpStatus.OK)
				.body(userService.refreshSession(refreshToken, response));
	}
}
