package org.stagepass.paymentservice.exception;

/**
 * INVALID WEBHOOK EXCEPTION
 *
 * Thrown by WebhookSignatureVerifier when the HMAC-SHA256 signature
 * on an incoming webhook request does not match.
 *
 * CAUSES:
 * - Incorrect webhook secret configured in application.properties
 * - Attacker attempting to forge a Razorpay webhook event
 * - Webhook body was modified in transit (extremely rare)
 * - Razorpay rotated the webhook secret (requires config update)
 *
 * HOW IT IS HANDLED:
 * GlobalExceptionHandler catches this and returns HTTP 400 Bad Request.
 * Note: We DO return 400 here (unlike payment failures which return 200).
 * A 400 signals to Razorpay that the request was malformed — but since
 * legitimate Razorpay webhooks always have valid signatures, a 400 here
 * means the request was not from Razorpay (likely an attack).
 * Razorpay will not retry on 400 — which is what we want for invalid requests.
 *
 * MONITORING:
 * Log at WARN level with the source IP when this exception is thrown.
 * Multiple failures from the same IP within a short window should
 * trigger an alert — it may indicate an active attack against the webhook endpoint.
 */
public class InvalidWebhookException extends RuntimeException {

    public InvalidWebhookException(String message) {
        super(message);
    }

    public InvalidWebhookException(String message, Throwable cause) {
        super(message, cause);
    }
}