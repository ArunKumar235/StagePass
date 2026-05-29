package org.stagepass.paymentservice.exception;

import lombok.Getter;
import lombok.Setter;

/**
 * DUPLICATE PAYMENT EXCEPTION
 *
 * Thrown by IdempotencyService when a charge or refund request arrives
 * with a bookingId / paymentId that has already been processed.
 *
 * This is NOT an error condition — it is expected and correct behaviour
 * when Booking Service's Feign client retries after a timeout.
 *
 * HOW IT IS HANDLED:
 * GlobalExceptionHandler catches this and returns HTTP 200 with the cached
 * response body — NOT a 4xx error. The caller (Booking Service) receives the
 * same successful/failed result as the original call and proceeds accordingly.
 *
 * WHEN IS THIS THROWN:
 * In practice, IdempotencyService returns Optional<> and PaymentService checks
 * it before proceeding — this exception is thrown only as a fallback for cases
 * where both Redis and DB checks somehow pass but a race condition causes a
 * DB UNIQUE constraint violation on booking_id in the payment_records table.
 *
 * The UNIQUE constraint on payment_records.booking_id is the ultimate guard —
 * even if all application-layer idempotency checks fail, the DB will reject
 * the second insert and this exception will be thrown and handled gracefully.
 */
@Getter
@Setter
public class DuplicatePaymentException extends RuntimeException {

    private final String bookingId;

    public DuplicatePaymentException(String bookingId) {
        super("Payment already processed for bookingId: " + bookingId);
        this.bookingId = bookingId;
    }

    public DuplicatePaymentException(String bookingId, String message) {
        super(message);
        this.bookingId = bookingId;
    }

    public String getBookingId() {
        return bookingId;
    }
}