package org.stagepass.notificationservice.dto;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record SeatDetail(

                String seatId,
                String rowLabel,
                int seatNumber,
                String tier,
                String sectionName,
                BigDecimal price

) {
}
