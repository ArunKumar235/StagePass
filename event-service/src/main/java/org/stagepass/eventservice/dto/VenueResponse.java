package org.stagepass.eventservice.dto;

import lombok.Builder;

import java.util.UUID;

@Builder
public record VenueResponse(

        UUID id,

        String name,

        String address,

        String city,

        String state,

        String country,

        int totalCapacity,

        String mapImageUrl,

        int eventCount
) { }
