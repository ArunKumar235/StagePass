package org.stagepass.eventservice.kafka;

import org.stagepass.eventservice.entity.SeatStatus;
import org.stagepass.eventservice.service.EventService;
import org.stagepass.eventservice.service.SeatService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BOOKING EVENT CONSUMER
 *
 * Listens to Kafka topics published by the Booking Service and updates
 * seat status in the Event Service's database accordingly.
 *
 * This is the ONLY place where seat status changes happen in response
 * to booking activity. The Booking Service NEVER writes to the seats table
 * directly — it publishes events, and this consumer applies the changes.
 * This is the microservices data ownership principle in practice.
 *
 * Topics consumed:
 *
 * booking-confirmed → seat becomes BOOKED (permanent)
 * Also checks if event is now fully booked → marks event SOLD_OUT
 *
 * booking-failed / booking-cancelled → seat becomes AVAILABLE again
 * Happens when payment fails or user cancels before completing checkout
 *
 * IDEMPOTENCY: Kafka can redeliver messages (at-least-once delivery).
 * SeatService.updateSeatStatus() checks the current status before applying
 * the update — processing the same event twice is safe and produces no side
 * effects.
 *
 * MANUAL ACKNOWLEDGMENT: We use manual Acknowledgment
 * (ack-mode=MANUAL_IMMEDIATE)
 * so we only commit the Kafka offset AFTER the DB update succeeds.
 * If the update fails, Kafka will redeliver the message for retry.
 */
@Component
public class BookingEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(BookingEventConsumer.class);

    @Autowired
    private SeatService seatService;
    @Autowired
    private EventService eventService;

    // ── BOOKING CONFIRMED ────────────────────────────────────────────────────

    /**
     * Marks the seat as BOOKED after payment is confirmed.
     *
     * Expected payload fields:
     * - bookingId: UUID of the booking (for logging)
     * - seatId: UUID of the seat to mark as BOOKED
     * - eventId: UUID of the event (for cache eviction and sold-out check)
     * - userId: UUID of the user who booked (for audit)
     */
    @KafkaListener(topics = "booking-confirmed", groupId = "${spring.kafka.consumer.group-id}", containerFactory = "manualAckListenerContainerFactory")
    public void onBookingConfirmed(ConsumerRecord<String, Map<String, Object>> record,
            Acknowledgment acknowledgment) {
        Map<String, Object> payload = record.value();

        try {
            String eventIdStr = (String) payload.get("eventId");
            String bookingId = (String) payload.get("bookingId");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> seatDetails = (List<Map<String, Object>>) payload.get("seatDetails");

            if (eventIdStr == null || seatDetails == null || seatDetails.isEmpty()) {
                log.error("Invalid booking-confirmed payload — missing eventId or seatDetails. " +
                        "Payload: {}", payload);
                acknowledgment.acknowledge(); // Ack to avoid infinite retry on bad payload
                return;
            }

            UUID eventId = UUID.fromString(eventIdStr);

            log.info("Processing booking-confirmed: bookingId={} eventId={} seatCount={}",
                    bookingId, eventId, seatDetails.size());

            // Update status to BOOKED for each seat in the booking
            for (Map<String, Object> seatMap : seatDetails) {
                String seatIdStr = (String) seatMap.get("seatId");
                if (seatIdStr != null) {
                    UUID seatId = UUID.fromString(seatIdStr);
                    seatService.updateSeatStatus(seatId, eventId, SeatStatus.BOOKED);
                }
            }

            // Check if event is now fully sold out
            long availableSeats = seatService.countAvailableSeatsForEvent(eventId);
            if (availableSeats == 0) {
                log.info("All seats booked for eventId={} — marking SOLD_OUT", eventId);
                eventService.markAsSoldOut(eventId);
            }

            // Commit offset only after successful DB update
            acknowledgment.acknowledge();

            log.info("booking-confirmed processed successfully: bookingId={} eventId={}",
                    bookingId, eventId);

        } catch (Exception e) {
            log.error("Failed to process booking-confirmed: payload={} error={}",
                    payload, e.getMessage(), e);
            // Do NOT acknowledge — Kafka will redeliver for retry
            // After max retries, message goes to Dead Letter Topic (DLT)
        }
    }

    // ── BOOKING FAILED ───────────────────────────────────────────────────────

    /**
     * Releases a seat back to AVAILABLE when payment fails.
     *
     * This happens when:
     * - Payment Service declines the charge
     * - Redis TTL lock expires (user abandoned checkout) — handled separately
     * by a Quartz job in Booking Service that publishes seat-released
     *
     * Expected payload fields:
     * - seatId: UUID of the seat to release
     * - eventId: UUID of the event
     * - reason: String describing why the booking failed (for logging)
     */
    @KafkaListener(topics = "booking-failed", groupId = "${spring.kafka.consumer.group-id}", containerFactory = "manualAckListenerContainerFactory")
    public void onBookingFailed(ConsumerRecord<String, Map<String, Object>> record,
            Acknowledgment acknowledgment) {
        Map<String, Object> payload = record.value();

        try {
            String eventIdStr = (String) payload.get("eventId");
            String bookingId = (String) payload.get("bookingId");
            @SuppressWarnings("unchecked")
            List<String> seatIds = (List<String>) payload.get("seatIds");
            String reason = (String) payload.getOrDefault("reason", "unknown");

            if (eventIdStr == null || seatIds == null || seatIds.isEmpty()) {
                log.error("Invalid booking-failed payload. Payload: {}", payload);
                acknowledgment.acknowledge();
                return;
            }

            UUID eventId = UUID.fromString(eventIdStr);

            log.info("Processing booking-failed: bookingId={} eventId={} reason={} seatCount={}",
                    bookingId, eventId, reason, seatIds.size());

            // Release each seat back to AVAILABLE
            for (String seatIdStr : seatIds) {
                UUID seatId = UUID.fromString(seatIdStr);
                seatService.updateSeatStatus(seatId, eventId, SeatStatus.AVAILABLE);
            }

            acknowledgment.acknowledge();

            log.info("booking-failed processed: bookingId={} released {} seats back to AVAILABLE", bookingId,
                    seatIds.size());

        } catch (Exception e) {
            log.error("Failed to process booking-failed: payload={} error={}",
                    payload, e.getMessage(), e);
        }
    }

    // ── BOOKING CANCELLED ────────────────────────────────────────────────────

    /**
     * Releases a seat when a confirmed booking is later cancelled by the user.
     *
     * Different from booking-failed:
     * - booking-failed = payment never succeeded (seat was LOCKED, now AVAILABLE)
     * - booking-cancelled = booking was confirmed, user later requested
     * cancellation
     * (seat was BOOKED, now AVAILABLE — after refund is processed)
     *
     * Also re-checks sold-out status: if event was SOLD_OUT, it reverts to
     * PUBLISHED.
     */
    @KafkaListener(topics = "booking-cancelled", groupId = "${spring.kafka.consumer.group-id}", containerFactory = "manualAckListenerContainerFactory")
    public void onBookingCancelled(ConsumerRecord<String, Map<String, Object>> record,
            Acknowledgment acknowledgment) {
        Map<String, Object> payload = record.value();

        try {
            String eventIdStr = (String) payload.get("eventId");
            String bookingId = (String) payload.get("bookingId");
            @SuppressWarnings("unchecked")
            List<String> seatIds = (List<String>) payload.get("seatIds");

            if (eventIdStr == null || seatIds == null || seatIds.isEmpty()) {
                log.error("Invalid booking-cancelled payload. Payload: {}", payload);
                acknowledgment.acknowledge();
                return;
            }

            UUID eventId = UUID.fromString(eventIdStr);

            log.info("Processing booking-cancelled: bookingId={} eventId={} seatCount={}", bookingId, eventId,
                    seatIds.size());

            // Release each seat back to AVAILABLE
            for (String seatIdStr : seatIds) {
                UUID seatId = UUID.fromString(seatIdStr);
                seatService.updateSeatStatusToAvailable(seatId, eventId, SeatStatus.AVAILABLE);
            }

            // If event was SOLD_OUT, re-check and possibly revert to PUBLISHED
            long availableSeats = seatService.countAvailableSeatsForEvent(eventId);
            if (availableSeats > 0) {
                eventService.revertFromSoldOut(eventId);
            }

            acknowledgment.acknowledge();

            log.info("booking-cancelled processed: bookingId={} released {} seats to AVAILABLE", bookingId,
                    seatIds.size());

        } catch (Exception e) {
            log.error("Failed to process booking-cancelled: payload={} error={}",
                    payload, e.getMessage(), e);
        }
    }
}
