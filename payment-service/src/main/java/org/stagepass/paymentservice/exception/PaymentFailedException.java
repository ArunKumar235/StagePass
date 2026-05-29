package org.stagepass.paymentservice.exception;

import lombok.Getter;
import lombok.Setter;

/**
 * PAYMENT FAILED EXCEPTION
 *
 * Thrown by RazorpayGatewayService when:
 * - Razorpay declines the payment (card declined, insufficient funds, expired card)
 * - Razorpay returns an unexpected status (not "captured")
 * - Razorpay API throws a RazorpayException (network error, auth failure)
 *
 * Caught by PaymentService — the caught exception causes the PaymentRecord
 * to be marked FAILED and a ChargeResponse with status=FAILED to be returned.
 *
 * Caught by GlobalExceptionHandler for any case that escapes PaymentService —
 * maps to HTTP 200 with FAILED status in body (not 4xx — see GlobalExceptionHandler
 * for the reasoning on why payment failures return 200).
 *
 * Razorpay error code categories:
 *   BAD_REQUEST_ERROR  — invalid input (expired card, wrong CVV)
 *   GATEWAY_ERROR      — payment gateway/bank issue
 *   SERVER_ERROR       — Razorpay internal error (retry after delay)
 */
@Getter
@Setter
public class PaymentFailedException extends RuntimeException {

    /**
     * -- GETTER --
     *  Returns the Razorpay error code category.
     *  Used by GlobalExceptionHandler to build the error response
     *  and by logging to categorise failures in analytics.
     */
    private final String gatewayErrorCode;

    /**
     * @param message       human-readable failure description (from Razorpay)
     * @param gatewayErrorCode  Razorpay error code category
     */
    public PaymentFailedException(String message, String gatewayErrorCode) {
        super(message);
        this.gatewayErrorCode = gatewayErrorCode;
    }

    public PaymentFailedException(String message) {
        super(message);
        this.gatewayErrorCode = "UNKNOWN";
    }

    /**
     * Whether this failure is likely retriable by the user.
     * SERVER_ERROR and GATEWAY_ERROR may succeed on retry;
     * BAD_REQUEST_ERROR (wrong card details) will not.
     */
    public boolean isRetriable() {
        return "SERVER_ERROR".equals(gatewayErrorCode) ||
                "GATEWAY_ERROR".equals(gatewayErrorCode);
    }
}