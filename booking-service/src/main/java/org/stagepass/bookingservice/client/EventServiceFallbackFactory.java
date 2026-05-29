package org.stagepass.bookingservice.client;

import org.stagepass.bookingservice.dto.SeatValidationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * EVENT SERVICE FALLBACK FACTORY
 *
 * Provides fallback behavior when Event Service is unreachable or throws 5xx.
 * Spring Cloud OpenFeign calls this factory to create a fallback implementation
 * of EventServiceClient when a call fails.
 *
 * Using FallbackFactory (not just @FeignClient fallback) gives us access to
 * the cause exception — we can log WHY the fallback triggered (timeout vs 503 vs etc.)
 * which is critical for debugging production incidents.
 *
 * FALLBACK BEHAVIOR:
 * validateSeats() returns allAvailable=false — BookingService interprets this
 * as "seats are not available" and rejects the booking with 409.
 * This is a safe default: failing closed (reject bookings) is safer than
 * failing open (accepting bookings without validation).
 */
@Component
public class EventServiceFallbackFactory implements FallbackFactory<EventServiceClient> {

    private static final Logger log = LoggerFactory.getLogger(EventServiceFallbackFactory.class);

    @Override
    public EventServiceClient create(Throwable cause) {
        return new EventServiceClient() {

            @Override
            public SeatValidationResponse validateSeats(UUID eventId, List<UUID> seatIds) {
                log.error("Event Service fallback triggered for validateSeats: " +
                                "eventId={} seatCount={} cause={}",
                        eventId, seatIds.size(), cause.getMessage());

                // Safe fallback: report all seats as unavailable
                // BookingService will return 409 with a meaningful error message
                // all seats "unavailable"
                return SeatValidationResponse.builder()
                        .availableAll(false)
                        .unavailableSeatIds(seatIds)  // all seats "unavailable"
                        .totalPrice(BigDecimal.ZERO)
                        .seatDetails(Collections.emptyList())
                        .eventDate(null)
                        .build();
            }
        };
    }
}