package org.stagepass.userservice.dto;

import lombok.Builder;
import org.stagepass.userservice.entity.AuthProvider;
import org.stagepass.userservice.entity.Role;

import java.time.Instant;
import java.util.UUID;

@Builder
public record UserProfileDto(
        UUID id,
        String email,
        String username,
        Role role,
        AuthProvider authProvider,
        Instant createdAt,
        Instant updatedAt
) {
}

