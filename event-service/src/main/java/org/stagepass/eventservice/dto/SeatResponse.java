package org.stagepass.eventservice.dto;

import lombok.Builder;
import org.stagepass.eventservice.entity.SeatStatus;

import java.math.BigDecimal;
import java.util.UUID;

@Builder
public record SeatResponse(

        UUID seatId,

        int seatNumber,

        SeatStatus status,

        BigDecimal price

) { }
