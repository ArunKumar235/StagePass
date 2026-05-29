package org.stagepass.paymentservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.stagepass.paymentservice.dto.WebhookPayload;
import org.stagepass.paymentservice.exception.InvalidWebhookException;
import org.stagepass.paymentservice.security.WebhookSignatureVerifier;
import org.stagepass.paymentservice.service.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * WEBHOOK CONTROLLER
 *
 * Receives asynchronous payment events directly from Razorpay.
 * This endpoint is NOT routed through the API Gateway — Razorpay calls it directly.
 *
 * ── WHY WEBHOOKS? ────────────────────────────────────────────────────────────
 * Some payment methods (UPI, Netbanking) are asynchronous — the user initiates
 * the payment, but the bank takes a few seconds to seconds to confirm.
 * The /charge endpoint returns PENDING for these; the final result arrives via webhook.
 *
 * Razorpay events we handle:
 *   payment.captured  → payment succeeded (update PaymentRecord to SUCCESS)
 *   payment.failed    → payment declined  (update PaymentRecord to FAILED)
 *   refund.processed  → refund settled    (update RefundRecord to SUCCESS)
 *
 * ── CRITICAL: ALWAYS RETURN 200 ──────────────────────────────────────────────
 * Razorpay treats any non-200 response as a delivery failure and retries
 * with exponential backoff (up to 24 hours, ~30 attempts).
 * Even if our processing fails internally, return 200 immediately and
 * log the error for manual resolution. Do NOT return 4xx/5xx to Razorpay.
 *
 * ── SIGNATURE VERIFICATION ───────────────────────────────────────────────────
 * Every webhook is signed with HMAC-SHA256 using the webhook secret.
 * WebhookSignatureVerifier recomputes the HMAC and compares using constant-time
 * comparison. If verification fails → 400 (and log the source IP).
 *
 * ── RAW BODY REQUIREMENT ─────────────────────────────────────────────────────
 * The signature is computed on the RAW request body bytes.
 * We must read the body as a raw String BEFORE any JSON parsing.
 * If Spring parses JSON first (modifying whitespace/ordering), the HMAC won't match.
 * This is why the parameter is @RequestBody String (raw) not @RequestBody WebhookPayload.
 */
@RestController
@RequestMapping("/payments")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    @Autowired private WebhookSignatureVerifier signatureVerifier;
    @Autowired private WebhookService           webhookService;
    @Autowired private ObjectMapper             objectMapper;

    /**
     * POST /payments/webhook
     *
     * Entry point for all Razorpay webhook events.
     * Permitted without Gateway header auth — security is HMAC-based.
     *
     * @param rawBody   raw JSON string (must stay raw for HMAC verification)
     * @param signature X-Razorpay-Signature header value from Razorpay
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false)
            String signature) {

        // ── STEP 1: VERIFY SIGNATURE ──────────────────────────────────────
        // Reject immediately if signature is missing or invalid.
        // Log the event — repeated failures from same IP may indicate an attack.
        if (signature == null || signature.isBlank()) {
            log.warn("Webhook received with no X-Razorpay-Signature header — rejected");
            throw new InvalidWebhookException("Missing X-Razorpay-Signature header");
        }

        if (!signatureVerifier.verify(rawBody, signature)) {
            log.warn("Webhook HMAC verification failed — signature={}", signature);
            throw new InvalidWebhookException("Invalid webhook signature");
        }

        // ── STEP 2: PARSE PAYLOAD ──────────────────────────────────────────
        WebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, WebhookPayload.class);
        } catch (Exception e) {
            log.error("Failed to parse webhook payload: {}", e.getMessage());
            // Return 200 anyway — bad payload shouldn't cause Razorpay to retry
            return ResponseEntity.ok("Received (parse error logged)");
        }

        log.info("Webhook received: event={} paymentId={}",
                payload.getEvent(),
                payload.getPayload() != null &&
                        payload.getPayload().getPayment() != null
                        ? payload.getPayload().getPayment().getEntity().getId()
                        : "N/A");

        // ── STEP 3: PROCESS ASYNCHRONOUSLY ───────────────────────────────
        // Process inside a try-catch — any exception must NOT propagate to a 5xx.
        // Return 200 and log the failure for manual reconciliation.
        try {
            webhookService.process(payload);
        } catch (Exception e) {
            log.error("Webhook processing failed: event={} error={}",
                    payload.getEvent(), e.getMessage(), e);
            // Still return 200 — avoid Razorpay retry storm
        }

        // ── STEP 4: ALWAYS RETURN 200 ─────────────────────────────────────
        return ResponseEntity.ok("Webhook received");
    }
}