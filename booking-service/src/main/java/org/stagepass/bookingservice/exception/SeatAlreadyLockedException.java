package org.stagepass.bookingservice.exception;

import lombok.Getter;

import java.util.List;
import java.util.UUID;

/**
 * SEAT ALREADY LOCKED EXCEPTION
 *
 * Thrown by BookingService.initiateBooking() when:
 * 1. Event Service validation reports one or more seats are not AVAILABLE
 * 2. Redis lock acquisition fails for one or more seats (concurrent booking race)
 *
 * Includes the specific seatIds that are unavailable so the frontend can
 * highlight exactly which seats are taken on the seat map — much better UX
 * than a generic "seat unavailable" message.
 *
 * Caught by GlobalExceptionHandler → HTTP 409 Conflict.
 *
 * Why 409 (Conflict) and not 400 (Bad Request)?
 * The request itself is valid — the problem is a resource state conflict
 * (seat is locked by someone else). 409 is semantically correct for this.
 */
@Getter
public class SeatAlreadyLockedException extends RuntimeException {

    private final List<UUID> lockedSeatIds;

    /**
     * @param lockedSeatIds the specific seat UUIDs that could not be locked
     */
    public SeatAlreadyLockedException(List<UUID> lockedSeatIds) {
        super(buildMessage(lockedSeatIds));
        this.lockedSeatIds = lockedSeatIds;
    }

    public SeatAlreadyLockedException(String message) {
        super(message);
        this.lockedSeatIds = List.of();
    }

    private static String buildMessage(List<UUID> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            return "One or more seats are not available for booking.";
        }
        return "The following seat(s) are already locked or booked: " + seatIds;
    }
}