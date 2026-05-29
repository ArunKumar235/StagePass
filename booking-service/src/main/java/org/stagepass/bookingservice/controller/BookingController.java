package org.stagepass.bookingservice.controller;

import org.stagepass.bookingservice.dto.BookingResponse;
import org.stagepass.bookingservice.dto.CreateBookingRequest;
import org.stagepass.bookingservice.service.BookingService;
import org.stagepass.bookingservice.service.CancellationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BOOKING CONTROLLER
 *
 * REST endpoints for the booking lifecycle.
 * All endpoints require authentication — there are no public routes here
 * (unlike EventController where GET /events is public).
 *
 * Identity comes from X-User-Id header (via HeaderAuthFilter + UserContext).
 * This controller never reads userId from the request body — only from the
 * trust-stamped header. A user can only interact with their own bookings.
 *
 * Role enforcement:
 * - Regular users: create, view own, cancel own bookings
 * - ADMIN: additionally view all bookings for any event
 */
@RestController
@RequestMapping("/bookings")
public class BookingController {

    @Autowired private BookingService      bookingService;
    @Autowired private CancellationService cancellationService;

    // ── INITIATE BOOKING ──────────────────────────────────────────────────────

    /**
     * POST /bookings
     *
     * Initiates the booking Saga:
     *   validate seats → lock in Redis → charge payment → confirm
     *
     * Returns 202 Accepted (not 201 Created) because the booking is PENDING
     * when this response is sent. The final CONFIRMED/FAILED status arrives
     * asynchronously via Kafka. The client should poll GET /bookings/{id}
     * to check final status.
     *
     * However: in the current synchronous implementation, payment is called
     * inline (Feign) so the response already contains the final status.
     * 202 is still semantically correct — it signals "we received your request
     * and initiated processing" which is true even for synchronous Sagas.
     *
     * Returns 409 if any seat is already locked/booked (SeatAlreadyLockedException).
     */
    @PostMapping
    public ResponseEntity<BookingResponse> createBooking(
            @Valid @RequestBody CreateBookingRequest request) {

        BookingResponse response = bookingService.initiateBooking(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    // ── GET MY BOOKINGS ───────────────────────────────────────────────────────

    /**
     * GET /bookings/my
     *
     * Returns all bookings for the authenticated user, newest first.
     * Used for the "My Tickets" page.
     * userId read from X-User-Id header via UserContext — not from request.
     */
    @GetMapping("/my")
    public ResponseEntity<List<BookingResponse>> getMyBookings() {
        return ResponseEntity.ok(bookingService.getMyBookings());
    }

    // ── GET SINGLE BOOKING ────────────────────────────────────────────────────

    /**
     * GET /bookings/{bookingId}
     *
     * Returns a specific booking — but only if it belongs to the authenticated user.
     * Returns 404 for both "not found" and "belongs to someone else" cases
     * (intentionally — avoids leaking booking existence for other users).
     *
     * Also used by the client to poll booking status after initiating:
     *   PENDING  → still processing
     *   CONFIRMED → payment succeeded, ticket ready
     *   FAILED    → payment failed, try again
     */
    @GetMapping("/{bookingId}")
    public ResponseEntity<BookingResponse> getBookingById(
            @PathVariable UUID bookingId) {

        return ResponseEntity.ok(bookingService.getBookingById(bookingId));
    }

    // ── CANCEL BOOKING ────────────────────────────────────────────────────────

    /**
     * DELETE /bookings/{bookingId}
     *
     * Cancels a CONFIRMED booking. Initiates refund if within policy window.
     * Returns a message string describing the outcome (refunded or not).
     *
     * Returns 404 if booking not found or belongs to another user.
     * Returns 409 if booking is not in CONFIRMED status.
     */
    @DeleteMapping("/{bookingId}")
    public ResponseEntity<Map<String, String>> cancelBooking(
            @PathVariable UUID bookingId) {

        String outcome = cancellationService.cancelBooking(bookingId);
        return ResponseEntity.ok(Map.of("message", outcome));
    }

    // ── ADMIN: BOOKINGS BY EVENT ──────────────────────────────────────────────

    /**
     * GET /bookings/admin/event/{eventId}
     *
     * Returns all bookings for a given event — ADMIN only.
     * Used for event management dashboard and capacity reporting.
     * Not accessible to regular users or organisers.
     */
    @GetMapping("/admin/event/{eventId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<BookingResponse>> getBookingsByEvent(
            @PathVariable UUID eventId) {

        return ResponseEntity.ok(bookingService.getBookingsByEvent(eventId));
    }
}