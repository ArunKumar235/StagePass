package org.stagepass.notificationservice.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record BookingConfirmedEvent(

        String bookingId,
        String eventId,
        String userId,
        String userEmail,
        String userName,
        String eventName,
        String eventDate,
        String venueName,
        String venueAddress,
        List<SeatDetail> seatDetails,
        BigDecimal totalAmount,
        String paymentId

) {
}
