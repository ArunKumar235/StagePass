package org.stagepass.paymentservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * WEBHOOK PAYLOAD
 *
 * Mirrors the exact JSON structure Razorpay sends to POST /payments/webhook.
 *
 * Razorpay webhook JSON shape:
 * {
 *   "entity": "event",
 *   "account_id": "acc_xxx",
 *   "event": "payment.captured",
 *   "contains": ["payment"],
 *   "payload": {
 *     "payment": {             <- present for payment events (captured/failed)
 *       "entity": {
 *         "id": "pay_xxx",
 *         "amount": 50000,
 *         "currency": "INR",
 *         "status": "captured",
 *         "method": "card",
 *         "error_code": null,
 *         "error_description": null
 *       }
 *     },
 *     "refund": {                  <- present only for refund events (processed)
 *       "entity": {
 *         "id": "rfnd_xxx",
 *         "payment_id": "pay_xxx",
 *         "amount": 50000
 *       }
 *     }
 *   }
 * }
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) on every nested class is critical.
 * Razorpay regularly adds new fields to their webhook payload.
 * Without this annotation, Jackson throws UnrecognizedPropertyException on new fields,
 * breaking webhook processing and causing Razorpay to retry indefinitely.
 *
 * We only map fields we actually use — keeps the DTO lean.
 */
@Setter
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class WebhookPayload {

    /** Event type — drives routing in WebhookService */
    private String event;

    /** Outer entity type — always "event" */
    private String entity;

    /** The nested payload containing payment/refund data */
    private PayloadWrapper payload;

    // ── NESTED: PAYLOAD WRAPPER ───────────────────────────────────────────────

    @Setter
    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PayloadWrapper {

        /** Present for payment.captured and payment.failed events */
        private PaymentWrapper payment;

        /** Present for refund.processed events */
        private RefundWrapper refund;

    }

    // ── NESTED: PAYMENT WRAPPER ───────────────────────────────────────────────

    @Setter
    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PaymentWrapper {

        /** The actual payment data object */
        private PaymentEntity entity;

    }

    // ── NESTED: PAYMENT ENTITY ────────────────────────────────────────────────

    @Setter
    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PaymentEntity {

        // Getters and setters
        /** Razorpay's payment transaction ID — e.g. "pay_AbCd1234xxxx" */
        private String id;

        /** Amount in paise — divide by 100 for INR */
        private Long amount;

        /** Currency code — "INR" */
        private String currency;

        /** Payment status from Razorpay — "captured", "failed", "authorized" */
        private String status;

        /** Payment method — "card", "upi", "netbanking", "wallet" */
        private String method;

        /** Error code on failure — e.g. "BAD_REQUEST_ERROR", "GATEWAY_ERROR" */
        @JsonProperty("error_code")
        private String errorCode;

        /** Human-readable failure reason — e.g. "Insufficient funds" */
        @JsonProperty("error_description")
        private String errorDescription;

        /** Order ID this payment belongs to (if Razorpay Orders flow is used) */
        @JsonProperty("order_id")
        private String orderId;

    }

    // ── NESTED: REFUND WRAPPER ────────────────────────────────────────────────

    @Setter
    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RefundWrapper {

        private RefundEntity entity;

    }

    // ── NESTED: REFUND ENTITY ─────────────────────────────────────────────────

    @Setter
    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RefundEntity {

        /** Razorpay's refund transaction ID — e.g. "rfnd_AbCd1234xxxx" */
        private String id;

        /** The payment ID this refund belongs to */
        @JsonProperty("payment_id")
        private String paymentId;

        /** Refund amount in paise */
        private Long amount;

        /** Refund status — "processed", "pending", "failed" */
        private String status;

    }

}