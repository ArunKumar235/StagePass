package org.stagepass.eventservice.dto;

import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder
public record SeatMapResponse(

        UUID eventId,

        List<SectionResponse> sections

)
{ }
