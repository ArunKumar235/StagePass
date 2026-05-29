package org.stagepass.eventservice.dto;

import lombok.Builder;

@Builder
public record VenueRequest(

        String name,

        String address,

        String city,

        String state,

        String country,

        int totalCapacity,

        String mapImageUrl

) { }
