package org.stagepass.bookingservice.dto;

import lombok.Builder;
import org.stagepass.bookingservice.entity.BookingStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Builder
public record BookingResponse(

                UUID bookingId,

                UUID eventId,

                UUID userId,

                BookingStatus status,

                List<BookingItemResponse> items,

                BigDecimal totalAmount,

                String paymentId,

                String paymentMethod,

                LocalDateTime createdAt,

                LocalDateTime expiresAt,

                LocalDateTime confirmedAt,

                LocalDateTime cancelledAt) {
}
