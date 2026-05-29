package org.stagepass.bookingservice.client;

import org.stagepass.bookingservice.dto.ChargeRequest;
import org.stagepass.bookingservice.dto.ChargeResponse;
import org.stagepass.bookingservice.dto.RefundResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

/**
 * PAYMENT SERVICE CLIENT
 *
 * Feign client for calling the Payment Service.
 *
 * IDEMPOTENCY:
 * Both charge() and refund() are designed to be idempotent:
 * - charge() uses bookingId as the idempotency key — retrying the same
 *   bookingId after a timeout returns the original result without double-charging
 * - refund() uses paymentId as the idempotency key — same safety guarantee
 *
 * This is what makes it safe for FeignConfig's Retryer to retry on timeout.
 * Without idempotency keys, a retry after timeout would charge the user twice.
 *
 * FALLBACK:
 * If Payment Service is unreachable, the fallback factory returns a FAILED
 * ChargeResponse. BookingService then runs the compensating transaction
 * (release locks + mark FAILED) rather than leaving the booking stuck as PENDING.
 */
@FeignClient(
        name = "payment-service",
        fallbackFactory = PaymentServiceFallbackFactory.class
)
public interface PaymentServiceClient {

    /**
     * Charges the user for a booking.
     *
     * Called as Step 4 of the booking Saga.
     * Uses bookingId as idempotency key — safe to retry on network failure.
     *
     * Maps to: POST /payments/charge
     *
     * @param request ChargeRequest with bookingId (idempotency key), amount, token
     * @return ChargeResponse with status (SUCCESS/FAILED), paymentId, and gatewayPaymentId
     */
    @PostMapping("/payments/charge")
    ChargeResponse charge(@RequestBody ChargeRequest request);

    /**
     * Initiates a refund for a previously charged payment.
     *
     * Called by CancellationService after booking is cancelled.
     * Uses paymentId as idempotency key — retrying the same paymentId
     * returns the existing refund result without issuing a second refund.
     *
     * Maps to: POST /payments/refund
     *
     * @param paymentId the Payment Service transaction ID to refund
     * @param amount    the amount to refund (may be partial in future)
     * @return RefundResponse with status, refund transaction IDs, and failure reason
     */
    @PostMapping("/payments/refund")
    RefundResponse refund(
            @RequestParam("paymentId") String paymentId,
            @RequestParam("amount") BigDecimal amount
    );
}