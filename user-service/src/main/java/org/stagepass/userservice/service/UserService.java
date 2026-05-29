package org.stagepass.userservice.service;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.stagepass.userservice.dto.AuthResponse;
import org.stagepass.userservice.dto.LoginRequest;
import org.stagepass.userservice.dto.RegisterRequest;
import org.stagepass.userservice.dto.UpdateUserProfileRequest;
import org.stagepass.userservice.dto.UserProfileDto;
import org.stagepass.userservice.entity.Role;
import org.stagepass.userservice.exception.UserAlreadyExistsException;
import org.stagepass.userservice.entity.AuthProvider;
import org.stagepass.userservice.entity.User;
import org.stagepass.userservice.repository.UserRepository;
import org.stagepass.userservice.security.JwtTokenProvider;
import org.stagepass.userservice.kafka.UserEventPublisher;

import java.util.UUID;
import java.util.function.Function;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final Function<User, UserProfileDto> userProfileConverter;
    private final UserEventPublisher userEventPublisher;

    @Autowired
    public UserService(UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtTokenProvider jwtTokenProvider,
            Function<User, UserProfileDto> userProfileConverter,
            UserEventPublisher userEventPublisher) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userProfileConverter = userProfileConverter;
        this.userEventPublisher = userEventPublisher;
    }

    public UUID register(@Valid RegisterRequest request) {

        // check if user already exists
        if (userRepository.existsByEmail(request.email())) {
            throw new UserAlreadyExistsException(request.email());
        }

        User savedUser = userRepository.save(
                User.builder()
                        .email(request.email())
                        .username(request.username())
                        .passwordHash(passwordEncoder.encode(request.password()))
                        .authProvider(AuthProvider.LOCAL)
                        .role(Role.USER)
                        .build());

        // Publish user registration event for downstream services (e.g., Notification
        // Service)
        userEventPublisher.publishUserRegistered(savedUser);

        return savedUser.getId();
    }

    public @Nullable AuthResponse login(@Valid LoginRequest request, HttpServletResponse response) {

        // Authenticate credentials
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        // Load user to include claims (role, etc.)
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        String accessToken = jwtTokenProvider.generateToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user);

        ResponseCookie refreshTokenCookie = ResponseCookie.from("refresh_token", refreshToken)
                .httpOnly(true) // Blocks JavaScript access (Prevents XSS)
                .secure(true) // Forces HTTPS (Allows localhost automatically)
                .sameSite("Strict") // Blocks cross-site token sending (Prevents CSRF)
                .path("/auth") // Only sent to authentication endpoints
                .maxAge(7 * 24 * 60 * 60) // Cookie lifespan (e.g., 7 days in seconds)
                .build();

        // Add the cookie to the raw HTTP response headers
        response.addHeader("Set-Cookie", refreshTokenCookie.toString());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .role(user.getRole() != null ? user.getRole().toString() : "USER")
                .build();
    }

    public AuthResponse refreshSession(String refreshToken, HttpServletResponse response) {

        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token");
        }

        // 2. Extract the user identity (usually email or username) from the token
        UUID userId = jwtTokenProvider.getUserIdFromToken(refreshToken);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        // 3. Generate a brand new Access Token
        String newAccessToken = jwtTokenProvider.generateToken(user);

        // 4. OPTIONAL BUT RECOMMENDED: Rotate the Refresh Token
        // This invalidates the old one and generates a brand new one to prevent replay
        // attacks
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(user);

        ResponseCookie rotatedCookie = ResponseCookie.from("refresh_token", newRefreshToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/auth")
                .maxAge(7 * 24 * 60 * 60) // Keep the original duration or reset it
                .build();

        response.addHeader("Set-Cookie", rotatedCookie.toString());

        // 5. Return the new access token to the frontend memory
        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .role(user.getRole() != null ? user.getRole().toString() : "USER")
                .build();
    }

    public void logout(HttpServletResponse response) {
        // Overwrite the cookie with a Max-Age of 0 to tell the browser to delete it
        // immediately
        ResponseCookie clearCookie = ResponseCookie.from("refresh_token", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/auth")
                .maxAge(0) // 0 seconds means "delete this right now"
                .build();

        response.addHeader("Set-Cookie", clearCookie.toString());
    }

    public UserProfileDto getUserById(UUID userId) {
        return userProfileConverter.apply(getUserOrThrow(userId));
    }

    public UserProfileDto updateCurrentUser(UUID userId, @Valid UpdateUserProfileRequest request) {
        User user = getUserOrThrow(userId);
        user.setUsername(request.username());

        return userProfileConverter.apply(userRepository.save(user));
    }

    private User getUserOrThrow(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

}
