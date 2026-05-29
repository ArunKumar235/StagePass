package org.stagepass.eventservice.exception;

import lombok.Getter;
import org.stagepass.eventservice.entity.SeatStatus;

import java.util.UUID;

/**
 * SEAT NOT AVAILABLE EXCEPTION
 *
 * Thrown as a last-resort DB-level guard when a seat is requested
 * but is already LOCKED or BOOKED.
 *
 * In the normal flow, this exception should rarely trigger because:
 * - The Booking Service acquires a Redis TTL lock BEFORE attempting to confirm
 * - The @Version optimistic lock on Seat entity catches concurrent confirmations
 *
 * If this exception IS thrown, it means:
 * - A request bypassed the Booking Service and called the Event Service directly
 * - There's a bug in the Booking Service's lock logic
 * - A race condition slipped through both the Redis lock and optimistic lock
 *
 * Caught by GlobalExceptionHandler → HTTP 409 Conflict.
 */
@Getter
public class SeatNotAvailableException extends RuntimeException {

    private final UUID       seatId;
    private final SeatStatus currentStatus;

    public SeatNotAvailableException(UUID seatId, SeatStatus currentStatus) {
        super(String.format(
                "Seat %s is not available for booking. Current status: %s",
                seatId, currentStatus
        ));
        this.seatId        = seatId;
        this.currentStatus = currentStatus;
    }

    public SeatNotAvailableException(UUID seatId) {
        super("Seat " + seatId + " is not available for booking.");
        this.seatId        = seatId;
        this.currentStatus = null;
    }

}
