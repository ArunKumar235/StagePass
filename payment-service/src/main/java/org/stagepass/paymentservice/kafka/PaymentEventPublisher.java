package org.stagepass.paymentservice.kafka;

import org.stagepass.paymentservice.entity.PaymentRecord;
import org.stagepass.paymentservice.entity.RefundRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * PAYMENT EVENT PUBLISHER
 *
 * Publishes payment lifecycle events to Kafka for downstream consumers.
 *
 * NOTE ON ROLE IN THE SAGA:
 * In StagePass V1, the booking Saga is driven synchronously:
 * Booking Service calls /payments/charge → gets SUCCESS/FAILED → proceeds accordingly.
 * Kafka events from this service are supplementary — primarily for analytics.
 *
 * In a fully async Saga (V2), these events would drive the Saga transitions:
 * payment-success → Booking Service confirms the booking
 * payment-failed  → Booking Service releases seat locks
 *
 * TOPICS:
 * payment-success   → Analytics: track revenue, conversion rates
 * payment-failed    → Analytics: track failure rates by method/bank
 * refund-processed  → Analytics: track refund volume
 *
 * KEY: bookingId for payment events, paymentId for refund events.
 * Ensures related events land in the same partition for ordered consumption.
 */
@Component
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    private static final String TOPIC_PAYMENT_SUCCESS  = "payment-success";
    private static final String TOPIC_PAYMENT_FAILED   = "payment-failed";
    private static final String TOPIC_REFUND_PROCESSED = "refund-processed";

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    // ── PAYMENT SUCCESS ───────────────────────────────────────────────────────

    public void publishPaymentSuccess(PaymentRecord record) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentId",        record.getId().toString());
        payload.put("bookingId",         record.getBookingId().toString());
        payload.put("userId",            record.getUserId().toString());
        payload.put("amount",            record.getAmount());
        payload.put("currency",          record.getCurrency());
        payload.put("paymentMethod",     record.getMethod().name());
        payload.put("gatewayPaymentId",  record.getGatewayPaymentId());
        payload.put("occurredAt",        Instant.now().toString());

        send(TOPIC_PAYMENT_SUCCESS, record.getBookingId().toString(), payload);
    }

    // ── PAYMENT FAILED ────────────────────────────────────────────────────────

    public void publishPaymentFailed(PaymentRecord record) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentId",    record.getId().toString());
        payload.put("bookingId",    record.getBookingId().toString());
        payload.put("userId",       record.getUserId().toString());
        payload.put("amount",       record.getAmount());
        payload.put("method",       record.getMethod().name());
        payload.put("failureReason", record.getFailureReason());
        payload.put("occurredAt",   Instant.now().toString());

        send(TOPIC_PAYMENT_FAILED, record.getBookingId().toString(), payload);
    }

    // ── REFUND PROCESSED ──────────────────────────────────────────────────────

    public void publishRefundProcessed(RefundRecord refund) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("refundId",       refund.getId().toString());
        payload.put("paymentId",      refund.getPayment().getId().toString());
        payload.put("bookingId",      refund.getPayment().getBookingId().toString());
        payload.put("amountRefunded", refund.getAmount());
        payload.put("gatewayRefundId", refund.getGatewayRefundId());
        payload.put("occurredAt",     Instant.now().toString());

        send(TOPIC_REFUND_PROCESSED, refund.getPayment().getId().toString(), payload);
    }

    // ── PRIVATE ───────────────────────────────────────────────────────────────

    private void send(String topic, String key, Object payload) {
        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(topic, key, payload);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Kafka publish FAILED: topic={} key={} error={}",
                        topic, key, ex.getMessage());
            } else {
                log.debug("Kafka published: topic={} key={} partition={} offset={}",
                        topic, key,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}