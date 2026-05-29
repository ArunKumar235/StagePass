package org.stagepass.eventservice.dto;

import lombok.Builder;
import org.stagepass.eventservice.entity.EventStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Builder
public record EventResponse(

        UUID id,

        String title,

        String description,

        LocalDate eventDate,

        LocalTime doorsOpenTime,

        EventStatus status,

        String category,

        String bannerImageURL,

        VenueResponse venue,

        long availableSeatCount,

        int lowestPrice,

        List<SeatTierCount> seatTierCounts,

        UUID organizerID,

        Instant createdAt
)
{ }
