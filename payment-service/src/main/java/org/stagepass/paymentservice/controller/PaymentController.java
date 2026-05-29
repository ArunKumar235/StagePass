package org.stagepass.paymentservice.controller;

import org.stagepass.paymentservice.dto.ChargeRequest;
import org.stagepass.paymentservice.dto.ChargeResponse;
import org.stagepass.paymentservice.dto.RefundRequest;
import org.stagepass.paymentservice.dto.RefundResponse;
import org.stagepass.paymentservice.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * PAYMENT CONTROLLER
 *
 * REST endpoints called by Booking Service via Feign client.
 * All endpoints require authentication (X-User-Id header from Gateway).
 *
 * CRITICAL DESIGN DECISION — HTTP status for payment failures:
 * Both /charge and /refund return HTTP 200 even when payment fails.
 * The status field inside the response body (SUCCESS / FAILED) conveys outcome.
 *
 * Why not return 402 Payment Required on failure?
 * Feign maps 4xx/5xx responses to FeignException by default.
 * BookingService's catch block would need to inspect FeignException to
 * get the failure reason — messy and error-prone.
 * Returning 200 with status=FAILED keeps Feign transparent:
 * BookingService reads paymentResponse.getStatus() and branches accordingly.
 * This is a deliberate API contract between the two services.
 *
 * IDEMPOTENCY:
 * Both endpoints are idempotent via the bookingId / paymentId keys.
 * Feign retry after timeout → same result returned, no double charge/refund.
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    @Autowired
    private PaymentService paymentService;

    // ── CHARGE ────────────────────────────────────────────────────────────────

    /**
     * POST /payments/charge
     *
     * Charges a user for a booking. Called by Booking Service as Step 4 of the
     * Saga.
     *
     * Idempotent: same bookingId returns the same response regardless of how
     * many times called. Safe for Feign retries on network timeout.
     *
     * Always returns HTTP 200. Check response.status for SUCCESS or FAILED.
     */
    @PostMapping("/charge")
    public ResponseEntity<ChargeResponse> charge(
            @Valid @RequestBody ChargeRequest request) {

        ChargeResponse response = paymentService.charge(request);
        return ResponseEntity.ok(response);
    }

    // ── REFUND ────────────────────────────────────────────────────────────────

    /**
     * POST /payments/refund
     *
     * Initiates a refund for a previously successful payment.
     * Called by CancellationService in Booking Service.
     *
     * Idempotent: same paymentId + amount returns cached response.
     * Safe for retry after network failure.
     *
     * Always returns HTTP 200. Check response.status for SUCCESS or FAILED.
     * Note: Razorpay refunds take 5-7 business days — SUCCESS means
     * the refund was submitted, not that money has reached the user yet.
     */
    @PostMapping("/refund")
    public ResponseEntity<RefundResponse> refund(
            @RequestParam("paymentId") String paymentId,
            @RequestParam("amount") BigDecimal amount) {

        RefundRequest request = new RefundRequest(paymentId, amount, "BOOKING_CANCELLED");
        RefundResponse response = paymentService.refund(request);
        return ResponseEntity.ok(response);
    }

    // ── ADMIN ─────────────────────────────────────────────────────────────────

    /**
     * GET /payments/admin/booking/{bookingId}
     *
     * Returns the payment record for a booking. ADMIN only.
     * Used for customer support (verify payment status, find gateway transaction
     * ID).
     */
    @GetMapping("/admin/booking/{bookingId}")
    public ResponseEntity<ChargeResponse> getPaymentByBooking(
            @PathVariable String bookingId) {

        ChargeResponse response = paymentService.getByBookingId(bookingId);
        return ResponseEntity.ok(response);
    }
}