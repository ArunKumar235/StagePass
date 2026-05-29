package org.stagepass.bookingservice.client;

import org.stagepass.bookingservice.dto.ChargeRequest;
import org.stagepass.bookingservice.dto.ChargeResponse;
import org.stagepass.bookingservice.dto.RefundResponse;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * PAYMENT SERVICE FALLBACK FACTORY
 *
 * Provides fallback behavior when Payment Service is unreachable or throws 5xx.
 *
 * FALLBACK BEHAVIOR:
 *
 * charge() fallback → returns FAILED ChargeResponse
 *   BookingService sees FAILED → runs compensating transaction:
 *   release Redis seat locks → mark Booking as FAILED → publish booking-failed
 *   Result: clean failure, seats released, no orphaned locks
 *
 * refund() fallback → only used for transport-level failures
 *   - If the Payment Service returned a real HTTP error, rethrow it so the
 *     caller sees the proper non-200 response.
 *   - If the Payment Service is unreachable / times out, return FAILED so the
 *     booking cancellation can continue and be reconciled manually.
 *
 * WHY FAIL CLOSED FOR CHARGE, PARTIAL OPEN FOR REFUND:
 * - Charging: better to reject the booking (safe) than to charge without confirmation
 * - Refunding: better to cancel and reconcile later than to block user from cancelling
 */
@Component
public class PaymentServiceFallbackFactory implements FallbackFactory<PaymentServiceClient> {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceFallbackFactory.class);

    @Override
    public PaymentServiceClient create(Throwable cause) {
        return new PaymentServiceClient() {

            @Override
            public ChargeResponse charge(ChargeRequest request) {
                log.error("Payment Service fallback triggered for charge: " +
                                "bookingId={} amount={} cause={}",
                        request.bookingId(), request.amount(), cause.getMessage());

                // Return FAILED — BookingService will compensate (release locks, mark FAILED)
                return ChargeResponse.builder()
                        .paymentId(null)
                        .gatewayPaymentId(null)
                        .status("FAILED")
                        .failureReason("Payment Service is temporarily unavailable. Please try again.")
                        .amountCharged(request.amount())
                        .processedAt(null)
                        .build();
            }

            @Override
            public RefundResponse refund(String paymentId, BigDecimal amount) {
                log.error("Payment Service fallback triggered for refund: " +
                                "paymentId={} amount={} cause={}",
                        paymentId, amount, cause.getMessage());

                if (cause instanceof FeignException feignException) {
                    throw feignException;
                }

                // Return FAILED — CancellationService logs and continues with cancellation
                return RefundResponse.builder()
                        .refundId(null)
                        .gatewayRefundId(null)
                        .status("FAILED")
                        .failureReason("Refund could not be processed automatically. Will be reconciled manually.")
                        .amountRefunded(amount)
                        .processedAt(null)
                        .build();
            }
        };
    }
}