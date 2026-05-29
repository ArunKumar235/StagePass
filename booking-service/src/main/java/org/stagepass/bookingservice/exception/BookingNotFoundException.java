package org.stagepass.bookingservice.exception;

import lombok.Getter;

import java.util.UUID;

/**
 * BOOKING NOT FOUND EXCEPTION
 *
 * Thrown when:
 * 1. A bookingId does not exist in the database
 * 2. A booking exists but belongs to a different user (ownership violation)
 *
 * IMPORTANT: Both cases return HTTP 404 (not 403 for ownership violations).
 * Returning 403 "Forbidden" would reveal that the booking exists for someone else,
 * which leaks information. Returning 404 in both cases is the secure approach —
 * the caller can't distinguish "doesn't exist" from "not yours".
 *
 * Caught by GlobalExceptionHandler → HTTP 404 Not Found.
 */
@Getter
public class BookingNotFoundException extends RuntimeException {

    private final UUID bookingId;

    public BookingNotFoundException(UUID bookingId) {
        super("Booking not found: " + bookingId);
        this.bookingId = bookingId;
    }

    public BookingNotFoundException(String message) {
        super(message);
        this.bookingId = null;
    }

}