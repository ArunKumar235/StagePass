package org.stagepass.eventservice.dto;

import lombok.Builder;
import org.stagepass.eventservice.entity.SeatTier;

import java.util.List;

@Builder
public record SectionResponse(

        String sectionName,

        SeatTier seatTier,

        List<RowResponse> rows

)
{ }

