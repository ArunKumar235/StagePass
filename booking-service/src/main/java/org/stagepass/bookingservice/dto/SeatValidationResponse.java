package org.stagepass.bookingservice.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Builder
public record SeatValidationResponse(

        boolean availableAll,

        List<UUID> unavailableSeatIds,

        BigDecimal totalPrice,

        List<SeatDetail> seatDetails,

        LocalDate eventDate

) {

        public record SeatDetail(
                UUID seatId,
                UUID sectionId,
                String rowLabel,
                int seatNumber,
                String tier,
                BigDecimal price,
                String sectionName
        ) {
        }
}
