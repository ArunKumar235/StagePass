package org.stagepass.bookingservice.service;

import org.stagepass.bookingservice.client.PaymentServiceClient;
import org.stagepass.bookingservice.dto.RefundResponse;
import org.stagepass.bookingservice.entity.Booking;
import org.stagepass.bookingservice.entity.BookingStatus;
import org.stagepass.bookingservice.exception.BookingNotFoundException;
import org.stagepass.bookingservice.kafka.BookingEventPublisher;
import org.stagepass.bookingservice.repository.BookingRepository;
import org.stagepass.bookingservice.security.UserContext;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * CANCELLATION SERVICE
 *
 * Handles user-initiated booking cancellations.
 * Separate from BookingService to keep each class focused on one responsibility.
 *
 * ── CANCELLATION FLOW ───────────────────────────────────────────────────────
 *
 * 1. Fetch booking and verify ownership (user can only cancel their own booking)
 * 2. Verify booking is CONFIRMED (can't cancel PENDING or already-CANCELLED)
 * 3. Check refund eligibility (cancellation policy — time-based cutoff)
 * 4. Call Payment Service to initiate refund (idempotent via paymentId key)
 * 5. Update Booking status to CANCELLED
 * 6. Publish booking-cancelled to Kafka:
 *    → Event Service: release seat back to AVAILABLE
 *    → Notification Service: send cancellation confirmation email
 *
 * ── CANCELLATION POLICY ─────────────────────────────────────────────────────
 * configurable via application.properties:
 *   stagepass.booking.cancellation-cutoff-days (default: 1)
 *
 * If event date is within 1 day: no refund (full amount forfeited)
 * Otherwise: full refund initiated via Payment Service
 *
 * In production you'd have tiered refunds (100% > 72h, 50% 24-72h, 0% < 24h)
 * but a simple cutoff is sufficient for StagePass V1.
 */
@Service
public class CancellationService {

    private static final Logger log = LoggerFactory.getLogger(CancellationService.class);

    @Value("${stagepass.booking.cancellation-cutoff-days}")
    private long cancellationCutoffDays;

    @Autowired private BookingRepository     bookingRepository;
    @Autowired private PaymentServiceClient  paymentServiceClient;
    @Autowired private BookingEventPublisher bookingEventPublisher;
    @Autowired private UserContext           userContext;

    // ── CANCEL BOOKING ────────────────────────────────────────────────────────

    /**
     * Cancels a confirmed booking and initiates a refund if eligible.
     *
     * @param bookingId UUID of the booking to cancel
     * @return a message indicating the outcome (cancelled with/without refund)
     */
    @Transactional
    public String cancelBooking(UUID bookingId) {
        UUID userId = UUID.fromString(userContext.getUserId());

        // ── STEP 1: FETCH AND VALIDATE OWNERSHIP ─────────────────────────
        Booking booking = bookingRepository
                .findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        // ── STEP 2: VALIDATE BOOKING STATUS ──────────────────────────────
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new IllegalStateException(
                    "Only CONFIRMED bookings can be cancelled. " +
                            "Current status: " + booking.getStatus()
            );
        }

        // ── STEP 3: CHECK REFUND ELIGIBILITY ─────────────────────────────
        // Refund is allowed if the event is more than cutoffHours away.
        // eventDate is stored in the booking at creation time (denormalised).
        boolean refundEligible = isRefundEligible(booking);

        String outcome;

        if (refundEligible && booking.getPaymentId() != null) {
            // ── STEP 4A: INITIATE REFUND ──────────────────────────────────
            // Refund Service follows the same contract as charge(): business
            // outcomes come back as HTTP 200 with a status in the body.
            // Non-200 responses are real errors and should propagate.
            try {
                RefundResponse refundResponse = paymentServiceClient
                        .refund(booking.getPaymentId(), booking.getTotalAmount());

                if (refundResponse == null) {
                    throw new IllegalStateException("Refund Service returned no response.");
                }

                if ("SUCCESS".equals(refundResponse.status())) {
                    log.info("Refund successful: bookingId={} paymentId={} amount={}",
                            bookingId, booking.getPaymentId(), booking.getTotalAmount());
                    outcome = "Booking cancelled. Full refund of ₹" +
                            booking.getTotalAmount() + " initiated.";
                } else {
                    // Refund failed — still cancel the booking but flag for manual review
                    log.error("Refund failed: bookingId={} paymentId={} reason={}",
                            bookingId, booking.getPaymentId(),
                            refundResponse.failureReason());
                    outcome = "Booking cancelled. Refund processing failed — " +
                            "please contact support with bookingId: " + bookingId;
                }
            } catch (FeignException e) {
                // Downstream refund rejected the request or failed server-side.
                // Surface the real status to the caller instead of masking it.
                log.warn("Refund request rejected by Payment Service: bookingId={} paymentId={} status={} message={}",
                        bookingId, booking.getPaymentId(), e.status(), e.getMessage());
                throw e;
            } catch (Exception e) {
                // Transport-level failure or fallback that produced a business FAILED response.
                log.error("Payment Service unreachable during refund: bookingId={} error={}",
                        bookingId, e.getMessage());
                outcome = "Booking cancelled. Refund could not be processed — " +
                        "please contact support with bookingId: " + bookingId;
            }
        } else {
            // ── STEP 4B: NO REFUND ────────────────────────────────────────
            log.info("Cancellation without refund: bookingId={} refundEligible={}",
                    bookingId, refundEligible);
            outcome = "Booking cancelled. No refund applicable — " +
                    "cancellations within " + cancellationCutoffDays +
                    " days of the event are non-refundable.";
        }

        // ── STEP 5: UPDATE BOOKING STATUS ────────────────────────────────
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(LocalDateTime.now());
        bookingRepository.save(booking);

        // ── STEP 6: PUBLISH KAFKA EVENT ───────────────────────────────────
        // Event Service consumes → releases seat to AVAILABLE
        // Notification Service consumes → sends cancellation email
        bookingEventPublisher.publishBookingCancelled(booking);

        log.info("Booking cancelled: bookingId={} userId={} outcome={}",
                bookingId, userId, outcome);

        return outcome;
    }

    // ── PRIVATE HELPERS ───────────────────────────────────────────────────────

    /**
     * Checks if the booking is eligible for a refund based on how far
     * away the event is from now.
     *
     * A booking stores eventDate at creation time (denormalised from Event Service).
     * This avoids a cross-service call during cancellation.
     *
     * @param booking the booking to check
     * @return true if refund eligible, false if within non-refundable window
     */
    private boolean isRefundEligible(Booking booking) {
        if (booking.getEventDate() == null) {
            // No event date stored — allow refund (fail-safe)
            return true;
        }
        LocalDateTime cutoff = LocalDate.now()
                .plusDays(cancellationCutoffDays)
                .atStartOfDay();
        return booking.getEventDate().isAfter(cutoff);
    }
}