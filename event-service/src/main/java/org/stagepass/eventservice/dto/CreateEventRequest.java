package org.stagepass.eventservice.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Builder
public record CreateEventRequest(

                @NotBlank String title,

                @NotBlank String description,

                String category,

                UUID venueId,

                @Future LocalDate eventDate,

                LocalTime doorsOpenTime,

                List<SeatTierPricing> tierPricing,

                String bannerImageURL) {
}
