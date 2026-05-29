package org.stagepass.paymentservice.service;

import org.stagepass.paymentservice.dto.WebhookPayload;
import org.stagepass.paymentservice.entity.PaymentRecord;
import org.stagepass.paymentservice.entity.PaymentStatus;
import org.stagepass.paymentservice.entity.RefundRecord;
import org.stagepass.paymentservice.entity.RefundStatus;
import org.stagepass.paymentservice.kafka.PaymentEventPublisher;
import org.stagepass.paymentservice.repository.PaymentRecordRepository;
import org.stagepass.paymentservice.repository.RefundRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * WEBHOOK SERVICE
 *
 * Processes Razorpay async webhook events after HMAC signature verification.
 *
 * EVENTS HANDLED:
 *
 * payment.captured  — Payment succeeded (important for async methods like UPI)
 *   → Update PaymentRecord status to SUCCESS
 *   → Publish payment-success Kafka event (for analytics)
 *
 * payment.failed    — Payment declined by bank/gateway
 *   → Update PaymentRecord status to FAILED with reason
 *
 * refund.processed  — Razorpay has settled the refund
 *   → Update RefundRecord status to SUCCESS
 *
 * IDEMPOTENCY:
 * Razorpay can deliver the same webhook multiple times (at-least-once).
 * Every handler checks current status before updating — if already in target
 * state, skip silently. This makes every handler safe to call multiple times.
 *
 * RELATIONSHIP TO SYNCHRONOUS FLOW:
 * For CARD payments, the /charge endpoint returns SUCCESS synchronously.
 * For UPI/Netbanking, the /charge endpoint may return PENDING; the webhook
 * delivers the final result asynchronously.
 * The webhook handlers must work correctly in both cases — they check the
 * current status before updating, so a SUCCESS webhook on an already-SUCCESS
 * record is a no-op (not an error).
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    @Autowired private PaymentRecordRepository paymentRepo;
    @Autowired private RefundRecordRepository  refundRepo;
    @Autowired private PaymentEventPublisher   eventPublisher;

    /**
     * Routes a webhook payload to the appropriate handler based on event type.
     *
     * @param payload the parsed Razorpay webhook event
     */
    @Transactional
    public void process(WebhookPayload payload) {
        if (payload == null || payload.getEvent() == null) {
            log.warn("Received webhook with null event — skipping");
            return;
        }

        log.info("Processing webhook event: {}", payload.getEvent());

        switch (payload.getEvent()) {
            case "payment.captured" -> handlePaymentCaptured(payload);
            case "payment.failed"   -> handlePaymentFailed(payload);
            case "refund.processed" -> handleRefundProcessed(payload);
            default -> log.debug("Unhandled webhook event: {} — ignoring",
                    payload.getEvent());
        }
    }

    // ── PAYMENT CAPTURED ──────────────────────────────────────────────────────

    /**
     * Handles payment.captured — payment succeeded asynchronously.
     *
     * Finds the PaymentRecord by Razorpay's gatewayPaymentId and sets
     * status to SUCCESS if it's currently PENDING.
     *
     * IDEMPOTENCY: If status is already SUCCESS (synchronous flow already
     * handled it), this is a no-op — log and return.
     */
    private void handlePaymentCaptured(WebhookPayload payload) {
        String gatewayPaymentId = extractGatewayPaymentId(payload);
        if (gatewayPaymentId == null) return;

        Optional<PaymentRecord> optRecord =
                paymentRepo.findByGatewayPaymentId(gatewayPaymentId);

        if (optRecord.isEmpty()) {
            // May not have our internal record yet (rare race condition)
            // Razorpay will retry — we'll get it on the next attempt
            log.warn("payment.captured: no PaymentRecord found for gatewayPaymentId={}",
                    gatewayPaymentId);
            return;
        }

        PaymentRecord record = optRecord.get();

        // Idempotency check — already SUCCESS? Skip.
        if (record.getStatus() == PaymentStatus.SUCCESS) {
            log.debug("payment.captured idempotency: already SUCCESS for gatewayPaymentId={}",
                    gatewayPaymentId);
            return;
        }

        record.setStatus(PaymentStatus.SUCCESS);
        record.setUpdatedAt(LocalDateTime.now());
        paymentRepo.save(record);

        // Publish Kafka event for analytics
        eventPublisher.publishPaymentSuccess(record);

        log.info("payment.captured processed: gatewayPaymentId={} bookingId={}",
                gatewayPaymentId, record.getBookingId());
    }

    // ── PAYMENT FAILED ────────────────────────────────────────────────────────

    /**
     * Handles payment.failed — bank/gateway declined the payment.
     *
     * Updates PaymentRecord to FAILED with the error reason from Razorpay.
     * IDEMPOTENCY: If already FAILED, skip.
     */
    private void handlePaymentFailed(WebhookPayload payload) {
        String gatewayPaymentId = extractGatewayPaymentId(payload);
        if (gatewayPaymentId == null) return;

        Optional<PaymentRecord> optRecord =
                paymentRepo.findByGatewayPaymentId(gatewayPaymentId);

        if (optRecord.isEmpty()) {
            log.warn("payment.failed: no PaymentRecord found for gatewayPaymentId={}",
                    gatewayPaymentId);
            return;
        }

        PaymentRecord record = optRecord.get();

        if (record.getStatus() == PaymentStatus.FAILED) {
            log.debug("payment.failed idempotency: already FAILED for gatewayPaymentId={}",
                    gatewayPaymentId);
            return;
        }

        // Extract failure reason from webhook payload
        String failureReason = "Unknown failure";
        try {
            if (payload.getPayload() != null &&
                    payload.getPayload().getPayment() != null &&
                    payload.getPayload().getPayment().getEntity() != null) {
                failureReason = payload.getPayload().getPayment()
                        .getEntity().getErrorDescription();
            }
        } catch (Exception e) {
            log.warn("Could not extract failure reason from payload");
        }

        record.setStatus(PaymentStatus.FAILED);
        record.setFailureReason(failureReason);
        record.setUpdatedAt(LocalDateTime.now());
        paymentRepo.save(record);

        eventPublisher.publishPaymentFailed(record);

        log.info("payment.failed processed: gatewayPaymentId={} reason={}",
                gatewayPaymentId, failureReason);
    }

    // ── REFUND PROCESSED ──────────────────────────────────────────────────────

    /**
     * Handles refund.processed — Razorpay has settled the refund.
     *
     * Finds RefundRecord by Razorpay's refund ID and sets to SUCCESS.
     * IDEMPOTENCY: If already SUCCESS, skip.
     */
    private void handleRefundProcessed(WebhookPayload payload) {
        String gatewayRefundId = extractGatewayRefundId(payload);
        if (gatewayRefundId == null) return;

        Optional<RefundRecord> optRefund =
                refundRepo.findByGatewayRefundId(gatewayRefundId);

        if (optRefund.isEmpty()) {
            log.warn("refund.processed: no RefundRecord found for gatewayRefundId={}",
                    gatewayRefundId);
            return;
        }

        RefundRecord refund = optRefund.get();

        if (refund.getStatus() == RefundStatus.SUCCESS) {
            log.debug("refund.processed idempotency: already SUCCESS for gatewayRefundId={}",
                    gatewayRefundId);
            return;
        }

        refund.setStatus(RefundStatus.SUCCESS);
        refund.setProcessedAt(LocalDateTime.now());
        refundRepo.save(refund);

        eventPublisher.publishRefundProcessed(refund);

        log.info("refund.processed: gatewayRefundId={}", gatewayRefundId);
    }

    // ── PRIVATE HELPERS ───────────────────────────────────────────────────────

    private String extractGatewayPaymentId(WebhookPayload payload) {
        try {
            return payload.getPayload().getPayment().getEntity().getId();
        } catch (NullPointerException e) {
            log.error("Could not extract gatewayPaymentId from webhook payload: {}",
                    payload.getEvent());
            return null;
        }
    }

    private String extractGatewayRefundId(WebhookPayload payload) {
        try {
            return payload.getPayload().getRefund().getEntity().getId();
        } catch (NullPointerException e) {
            log.error("Could not extract gatewayRefundId from webhook payload: {}",
                    payload.getEvent());
            return null;
        }
    }
}