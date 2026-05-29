package org.stagepass.paymentservice.service;

import com.razorpay.*;
import org.stagepass.paymentservice.dto.ChargeRequest;
import org.stagepass.paymentservice.dto.ChargeResponse;
import org.stagepass.paymentservice.dto.RefundResponse;
import org.stagepass.paymentservice.exception.PaymentFailedException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * RAZORPAY GATEWAY SERVICE
 *
 * The ONLY class in StagePass that directly calls the Razorpay API.
 * All other classes interact with the payment gateway through this class.
 *
 * WHY ISOLATE GATEWAY CALLS HERE?
 * 1. Single responsibility — one class owns the external API dependency
 * 2. Easy to swap gateways — replace Razorpay with Stripe/PayU by rewriting
 *    just this class. Everything else (PaymentService, idempotency, DB) stays unchanged.
 * 3. Easy to mock in tests — inject a mock RazorpayGatewayService to avoid
 *    real Razorpay API calls in unit/integration tests.
 *
 * AMOUNT CONVERSION:
 * Razorpay works in paise (smallest currency unit).
 * ₹100.00 → 10000 paise. Always multiply by 100 before sending.
 * Always divide by 100 when reading amount back from Razorpay.
 *
 * RAZORPAY FLOW (simplified):
 * 1. Frontend creates a Razorpay Order (via Razorpay's JS SDK or your backend)
 * 2. User pays — Razorpay captures a payment and returns a payment_id
 * 3. Backend captures (or verifies) the payment using the payment_id
 * The paymentToken in ChargeRequest is the Razorpay payment_id from the frontend.
 */
@Service
public class RazorpayGatewayService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayGatewayService.class);

    @Autowired
    private RazorpayClient razorpayClient;

    // ── CHARGE ────────────────────────────────────────────────────────────────

    /**
     * Captures a Razorpay payment.
     *
     * In Razorpay's flow, the frontend collects payment details and creates
     * a payment (using Razorpay.js). The frontend sends the resulting
     * Razorpay payment_id as paymentToken to the backend.
     * The backend then "captures" the payment to finalise the charge.
     *
     * @param request   ChargeRequest from Booking Service
     * @param internalPaymentId our internal payment UUID (for logging)
     * @return ChargeResponse with status and gateway payment ID
     * @throws PaymentFailedException if Razorpay rejects the payment
     */
    public ChargeResponse charge(ChargeRequest request, UUID internalPaymentId) {
        String gatewayPaymentId = request.paymentToken(); // Razorpay payment_id from frontend
        // Convert to paise: ₹100.50 → 10050
        long amountInPaise = request.amount()
                .multiply(BigDecimal.valueOf(100))
                .longValue();

        log.info("Capturing Razorpay payment: gatewayPaymentId={} amountPaise={} internalId={}",
                gatewayPaymentId, amountInPaise, internalPaymentId);

        try {

            JSONObject captureRequest = new JSONObject();
            captureRequest.put("amount",   amountInPaise);
            captureRequest.put("currency", request.currency());

            Payment payment = razorpayClient.payments.capture(
                    gatewayPaymentId, captureRequest);

            String status = payment.get("status"); // "captured" on success

            if ("captured".equals(status)) {
                ChargeResponse response = ChargeResponse.builder()
                        .paymentId(internalPaymentId.toString())
                        .gatewayPaymentId(gatewayPaymentId)
                        .status("SUCCESS")
                        .amountCharged(request.amount())
                        .processedAt(LocalDateTime.now())
                        .build();

                log.info("Payment captured: gatewayPaymentId={} internalId={}",
                        gatewayPaymentId, internalPaymentId);

                return response;

            } else {
                // Unexpected status from Razorpay — treat as failure
                String errorDesc = payment.get("error_description");
                throw new PaymentFailedException(
                        "Payment not captured. Status: " + status +
                                ". Reason: " + errorDesc,
                        payment.get("error_code")
                );
            }

        } catch (RazorpayException e) {
            log.error("Razorpay API error during capture: gatewayPaymentId={} error={}",
                    gatewayPaymentId, e.getMessage());
            throw new PaymentFailedException(
                    "Payment failed: " + e.getMessage(), "GATEWAY_ERROR");
        }
    }

    // ── REFUND ────────────────────────────────────────────────────────────────

    /**
     * Initiates a refund for a previously captured payment.
     *
     * @param gatewayPaymentId  Razorpay payment_id of the original charge
     * @param amount            amount to refund (INR, not paise — we convert internally)
     * @param internalRefundId  our internal refund UUID (for logging and tracking)
     * @return RefundResponse with gateway refund ID and status
     */
    public RefundResponse refund(String gatewayPaymentId, BigDecimal amount,
                                 UUID internalRefundId) {
        int amountInPaise = amount.multiply(BigDecimal.valueOf(100)).intValue();

        log.info("Initiating Razorpay refund: gatewayPaymentId={} amountPaise={} refundId={}",
                gatewayPaymentId, amountInPaise, internalRefundId);

        try {
            JSONObject refundRequest = new JSONObject();
            refundRequest.put("amount", amountInPaise);
            refundRequest.put("notes",
                    new JSONObject().put("internal_refund_id", internalRefundId.toString()));

            Refund refund = razorpayClient.payments.refund(gatewayPaymentId, refundRequest);

            String gatewayRefundId = refund.get("id"); // Razorpay refund ID

            RefundResponse response = RefundResponse.builder()
                    .refundId(internalRefundId.toString())
                    .gatewayRefundId(gatewayRefundId)
                    .status("SUCCESS")
                    .amountRefunded(amount)
                    .processedAt(LocalDateTime.now())
                    .build();

            log.info("Refund initiated: gatewayRefundId={} gatewayPaymentId={}",
                    gatewayRefundId, gatewayPaymentId);

            return response;

        } catch (RazorpayException e) {
            log.error("Razorpay API error during refund: gatewayPaymentId={} error={}",
                    gatewayPaymentId, e.getMessage());

            return RefundResponse.failed(
                    internalRefundId.toString(), "Refund failed: " + e.getMessage());
        }
    }
}