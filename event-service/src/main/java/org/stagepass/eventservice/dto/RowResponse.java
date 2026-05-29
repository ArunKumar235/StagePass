package org.stagepass.eventservice.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record RowResponse(

        String rowLabel,

        List<SeatResponse> seats

)
{ }

