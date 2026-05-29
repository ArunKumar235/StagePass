package org.stagepass.bookingservice.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.UUID;

@Builder
public record BookingItemResponse(

        UUID seatId,

        UUID sectionId,

        String rowLabel,

        int seatNumber,

        String tier,

        String sectionName,

        BigDecimal price


) {
}
