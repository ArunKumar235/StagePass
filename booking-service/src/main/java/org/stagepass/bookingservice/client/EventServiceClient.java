package org.stagepass.bookingservice.client;

import org.stagepass.bookingservice.dto.SeatValidationResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

/**
 * EVENT SERVICE CLIENT
 *
 * Feign client for calling the Event Service.
 * Spring Cloud OpenFeign generates the HTTP client implementation at runtime.
 * The @FeignClient name ("event-service") is the Eureka service ID —
 * Feign resolves it to a live instance URL via service discovery.
 *
 * FALLBACK FACTORY:
 * If Event Service is unreachable or returns 5xx, the fallback factory
 * provides a safe default response. For seat validation, the fallback
 * returns allAvailable=false — BookingService treats this as "seats unavailable"
 * and rejects the booking with 409 rather than throwing an unhandled exception.
 *
 * This is the circuit breaker pattern: when Event Service is down,
 * new bookings fail gracefully instead of cascading errors through the system.
 *
 * TIMEOUT:
 * Configured in FeignConfig.java — 2s connect, 5s read.
 * Event Service seat validation is a DB read — should return well within 5s.
 * If it doesn't, the fallback kicks in.
 */
@FeignClient(
        name = "event-service",
        fallbackFactory = EventServiceFallbackFactory.class
)
public interface EventServiceClient {

    /**
     * Validates that all requested seats are AVAILABLE and returns pricing.
     *
     * Called as Step 1 of the booking Saga.
     * Authoritative price comes from Event Service — never trust client-sent price.
     *
     * Maps to: GET /events/{eventId}/seats/validate?seatIds=id1,id2,...
     *
     * @param eventId UUID of the event
     * @param seatIds list of seat UUIDs to validate
     * @return SeatValidationResponse with allAvailable flag, unavailable IDs, total price
     */
    @GetMapping("/events/{eventId}/seats/validate")
    SeatValidationResponse validateSeats(
            @PathVariable("eventId") UUID eventId,
            @RequestParam("seatIds") List<UUID> seatIds
    );
}