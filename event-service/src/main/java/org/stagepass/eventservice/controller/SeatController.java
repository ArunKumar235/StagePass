package org.stagepass.eventservice.controller;

import org.stagepass.eventservice.dto.SeatMapResponse;
import org.stagepass.eventservice.dto.SeatTierCount;
import org.stagepass.eventservice.dto.SeatValidationResponse;
import org.stagepass.eventservice.service.SeatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * SEAT CONTROLLER
 *
 * Exposes seat map data under /events/{eventId}/seats.
 * All endpoints are public — viewing a seat map requires no authentication.
 * (Only booking a seat requires auth, which is handled by Booking Service.)
 *
 * This is the highest-traffic controller in the service during a flash sale.
 * Every user refreshing the seat picker hits GET /events/{id}/seats.
 * Redis caching (30s TTL in CacheConfig) is essential here.
 *
 * Note: Seat status is NEVER changed via these endpoints.
 * Status transitions (AVAILABLE → LOCKED → BOOKED) happen via Kafka events
 * consumed by BookingEventConsumer — never through a REST call to this service.
 */
@RestController
@RequestMapping("/events/{eventId}/seats")
public class SeatController {

    @Autowired
    private SeatService seatService;

    // ── FULL SEAT MAP ─────────────────────────────────────────────────────────

    /**
     * GET /events/{eventId}/seats
     *
     * Returns the complete seat map for an event, structured for frontend
     * rendering:
     * sections → rows → seats (with id, number, status, price per seat)
     *
     * This is what the interactive seat picker UI calls to render the grid.
     * Cached 30 seconds in Redis — evicted when a booking-confirmed/failed event
     * arrives.
     *
     * A 10,000-seat venue returns ~10,000 seat objects — consider pagination for
     * very large venues (out of scope for V1).
     */
    @GetMapping
    public ResponseEntity<SeatMapResponse> getSeatMap(
            @PathVariable UUID eventId) {

        return ResponseEntity.ok(seatService.getSeatMap(eventId));
    }

    // ── AVAILABLE COUNT BY TIER ───────────────────────────────────────────────

    /**
     * GET /events/{eventId}/seats/available-count
     *
     * Returns available seat count grouped by tier.
     * Example response: {"GENERAL": 245, "VIP": 12, "PREMIUM": 0}
     *
     * Used for:
     * - "Only X seats left!" badge on the event listing card
     * - Disabling a tier button when count = 0
     *
     * Cached 15 seconds (very short — changes rapidly during active sales).
     * Also exposed from EventController.getAvailableSeatCount() — same backing
     * service.
     */
    @GetMapping("/available-count")
    public ResponseEntity<List<SeatTierCount>> getAvailableCount(
            @PathVariable UUID eventId) {

        return ResponseEntity.ok(seatService.getAvailableCountByTier(eventId));
    }

    // ── SEATS BY SECTION ──────────────────────────────────────────────────────

    /**
     * GET /events/{eventId}/seats/sections/{sectionId}
     *
     * Returns seats for a single section only.
     * Useful for large venues where the frontend renders one section at a time
     * instead of loading the full 10,000-seat map at once.
     *
     * Not cached separately — the full seat map cache covers this.
     */
    @GetMapping("/sections/{sectionId}")
    public ResponseEntity<SeatMapResponse> getSeatsBySection(
            @PathVariable UUID eventId,
            @PathVariable UUID sectionId) {

        return ResponseEntity.ok(seatService.getSeatMapBySection(eventId, sectionId));
    }

    // ── SEAT VALIDATION ───────────────────────────────────────────────────────
    /**
     * GET /events/{eventId}/seats/validate?seatIds=id1,id2,...
     *
     * Validates that all requested seats are AVAILABLE and returns pricing.
     * Called as Step 1 of the booking Saga.
     * Authoritative price comes from Event Service — never trust client-sent price.
     *
     * Not cached — must reflect real-time availability and pricing.
     */

    @GetMapping("/validate")
    public SeatValidationResponse validateSeats(
            @PathVariable("eventId") UUID eventId,
            @RequestParam("seatIds") List<UUID> seatIds) {

        return seatService.validateSeats(eventId, seatIds);
    }
}