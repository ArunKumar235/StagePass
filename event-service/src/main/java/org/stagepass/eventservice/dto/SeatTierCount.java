package org.stagepass.eventservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import org.stagepass.eventservice.entity.SeatTier;

@Builder
public record SeatTierCount (
        @NotBlank
        SeatTier tier,

        @NotNull
        long seatsAvailable
) { }
