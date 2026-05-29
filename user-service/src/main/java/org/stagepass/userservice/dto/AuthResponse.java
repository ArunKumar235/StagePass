package org.stagepass.userservice.dto;

import lombok.Builder;

@Builder
public record AuthResponse(

        String accessToken,

        String role

) {
}
