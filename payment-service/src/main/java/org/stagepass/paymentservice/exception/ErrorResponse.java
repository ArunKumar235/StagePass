package org.stagepass.paymentservice.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * ERROR RESPONSE
 *
 * Standard error body returned for all API errors in the Payment Service.
 * Identical shape to Event Service and Booking Service — consistent across StagePass.
 *
 * Payment-specific addition: gatewayErrorCode — the Razorpay error category
 * (BAD_REQUEST_ERROR, GATEWAY_ERROR, SERVER_ERROR) when a payment fails.
 * Useful for the frontend to show appropriate messaging:
 * - BAD_REQUEST_ERROR → "Check your card details and try again"
 * - GATEWAY_ERROR     → "Your bank declined this transaction. Try a different card."
 * - SERVER_ERROR      → "Payment gateway unavailable. Please try again in a moment."
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
public class ErrorResponse {

    private int                 status;
    private String              error;
    private String              message;
    private String              path;
    private String              traceId;
    private String              timestamp;
    private Map<String, String> fieldErrors;

    /** Razorpay error code — only present for PaymentFailedException responses */
    private String              gatewayErrorCode;

    /** Whether the caller can retry — only present for PaymentFailedException */
    private Boolean             retriable;

}