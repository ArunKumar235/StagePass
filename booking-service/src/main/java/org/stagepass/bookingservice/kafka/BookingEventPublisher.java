package org.stagepass.bookingservice.kafka;

import org.stagepass.bookingservice.entity.Booking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * BOOKING EVENT PUBLISHER
 *
 * Publishes Kafka events for every significant transition in the booking lifecycle.
 * These events drive the rest of the StagePass system — no direct service-to-service
 * calls beyond the initial Feign calls in BookingService.
 *
 * TOPICS AND THEIR CONSUMERS:
 *
 * booking-confirmed
 *   → Event Service:        mark seats as BOOKED in seats table
 *   → Notification Service: send ticket confirmation email + PDF attachment
 *
 * booking-failed
 *   → Event Service:        release seats back to AVAILABLE
 *
 * booking-cancelled
 *   → Event Service:        release seats back to AVAILABLE (BOOKED → AVAILABLE)
 *   → Notification Service: send cancellation confirmation email
 *
 * KEY STRATEGY:
 * All events use bookingId as the Kafka message key.
 * This ensures all events for the same booking land in the same partition,
 * preserving ordering: booking-confirmed always processed before booking-cancelled.
 *
 * RELIABILITY:
 * Producer is configured with acks=all and idempotence=true (in KafkaConfig).
 * Delivery failures are logged. For truly critical events (booking-confirmed),
 * consider the Outbox pattern: write event to DB first, then publish.
 * This guarantees the event is never lost even if Kafka is temporarily down.
 */
@Component
public class BookingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(BookingEventPublisher.class);

    private static final String TOPIC_BOOKING_CONFIRMED  = "booking-confirmed";
    private static final String TOPIC_BOOKING_FAILED     = "booking-failed";
    private static final String TOPIC_BOOKING_CANCELLED  = "booking-cancelled";

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    // ── BOOKING CONFIRMED ────────────────────────────────────────────────────

    /**
     * Published when payment succeeds and booking is CONFIRMED.
     *
     * Payload includes individual seatIds so Event Service can update
     * each seat's status to BOOKED, and Notification Service knows
     * which specific seats to print on the ticket.
     */
    public void publishBookingConfirmed(Booking booking) {
        List<Map<String, Object>> seatDetails = booking.getItems().stream()
                .map(item -> {
                    Map<String, Object> seat = new HashMap<>();
                    seat.put("seatId",     item.getSeatId().toString());
                    seat.put("sectionId",  item.getSectionId().toString());
                    seat.put("rowLabel",   item.getRowLabel());
                    seat.put("seatNumber", item.getSeatNumber());
                    seat.put("tier",       item.getTier());
                    seat.put("sectionName", item.getSectionName());
                    seat.put("price",      item.getPrice());
                    return seat;
                })
                .collect(Collectors.toList());

        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId",    booking.getId().toString());
        payload.put("eventId",      booking.getEventId().toString());
        payload.put("userId",       booking.getUserId().toString());
        payload.put("paymentId",    booking.getPaymentId());
        payload.put("totalAmount",  booking.getTotalAmount());
        payload.put("seatDetails",  seatDetails);
        payload.put("occurredAt",   Instant.now().toString());

        send(TOPIC_BOOKING_CONFIRMED, booking.getId().toString(), payload);

        log.info("Published booking-confirmed: bookingId={} eventId={} seatCount={}",
                booking.getId(), booking.getEventId(), booking.getItems().size());
    }

    // ── BOOKING FAILED ───────────────────────────────────────────────────────

    /**
     * Published when payment fails or a Saga step cannot be completed.
     *
     * Includes individual seatIds so Event Service can release each seat
     * back to AVAILABLE. Includes reason for Notification Service to
     * optionally notify the user about the failure.
     */
    public void publishBookingFailed(Booking booking, String reason) {
        List<String> seatIds = booking.getItems().stream()
                .map(item -> item.getSeatId().toString())
                .collect(Collectors.toList());

        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId",  booking.getId().toString());
        payload.put("eventId",    booking.getEventId().toString());
        payload.put("userId",     booking.getUserId().toString());
        payload.put("seatIds",    seatIds);
        payload.put("reason",     reason != null ? reason : "Unknown failure");
        payload.put("occurredAt", Instant.now().toString());

        send(TOPIC_BOOKING_FAILED, booking.getId().toString(), payload);

        log.info("Published booking-failed: bookingId={} reason={} seatCount={}",
                booking.getId(), reason, seatIds.size());
    }

    // ── BOOKING CANCELLED ────────────────────────────────────────────────────

    /**
     * Published when a CONFIRMED booking is cancelled by the user.
     *
     * Seats were BOOKED (not just LOCKED) — Event Service must handle
     * the BOOKED → AVAILABLE transition differently from booking-failed
     * (which transitions LOCKED → AVAILABLE).
     */
    public void publishBookingCancelled(Booking booking) {
        List<String> seatIds = booking.getItems().stream()
                .map(item -> item.getSeatId().toString())
                .collect(Collectors.toList());

        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId",   booking.getId().toString());
        payload.put("eventId",     booking.getEventId().toString());
        payload.put("userId",      booking.getUserId().toString());
        payload.put("seatIds",     seatIds);
        payload.put("paymentId",   booking.getPaymentId());    // for refund tracking
        payload.put("totalAmount", booking.getTotalAmount());   // for refund amount
        payload.put("occurredAt",  Instant.now().toString());

        send(TOPIC_BOOKING_CANCELLED, booking.getId().toString(), payload);

        log.info("Published booking-cancelled: bookingId={} eventId={} seatCount={}",
                booking.getId(), booking.getEventId(), seatIds.size());
    }

    // ── PRIVATE SEND ─────────────────────────────────────────────────────────

    /**
     * Sends a Kafka message asynchronously with delivery callback.
     *
     * Failure is logged but not thrown — the DB transaction is already committed.
     * For booking-confirmed, consider adding an Outbox pattern for guaranteed delivery.
     */
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