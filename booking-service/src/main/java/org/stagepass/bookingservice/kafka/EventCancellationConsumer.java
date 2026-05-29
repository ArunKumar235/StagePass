package org.stagepass.bookingservice.kafka;

import org.stagepass.bookingservice.service.BookingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * EVENT CANCELLATION CONSUMER
 *
 * Listens to the "event-cancelled" Kafka topic published by Event Service
 * when an admin cancels an event.
 *
 * WHAT HAPPENS WHEN AN EVENT IS CANCELLED:
 *
 * Event Service publishes → event-cancelled
 *   ↓
 * This consumer receives it
 *   ↓
 * BookingService.cancelAllBookingsForEvent():
 *   - PENDING bookings  → release Redis locks + mark FAILED
 *   - CONFIRMED bookings → mark CANCELLED + publish booking-cancelled
 *                          (Payment Service: refund | Notification: alert users)
 *
 * IDEMPOTENCY:
 * BookingService.cancelAllBookingsForEvent() checks current booking status
 * before updating. If Kafka redelivers this message, already-CANCELLED
 * bookings are skipped — no double-processing, no errors.
 *
 * MANUAL ACKNOWLEDGMENT:
 * Kafka offset committed only after ALL bookings for the event are processed.
 * If processing fails partway, Kafka redelivers and we retry.
 * Combined with idempotency, this is safe.
 */
@Component
public class EventCancellationConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventCancellationConsumer.class);

    @Autowired
    private BookingService bookingService;

    /**
     * Handles event-cancelled events from Event Service.
     *
     * Expected payload fields:
     * - eventId:   UUID of the cancelled event (required)
     * - title:     event title (for logging only)
     * - eventDate: original event date (optional, for logging)
     * - occurredAt: when the cancellation happened
     */
    @KafkaListener(
            topics = "event-cancelled",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "manualAckListenerContainerFactory"
    )
    public void onEventCancelled(
            ConsumerRecord<String, Map<String, Object>> record,
            Acknowledgment acknowledgment) {

        Map<String, Object> payload = record.value();

        if (payload == null) {
            log.error("Received event-cancelled record with null payload (likely deserialization failure). Record: {}",
                    record);
            throw new IllegalStateException("Received null payload for event-cancelled record");
        }

        try {
            String eventIdStr = (String) payload.get("eventId");
            String title      = (String) payload.getOrDefault("title", "Unknown event");

            if (eventIdStr == null || eventIdStr.isBlank()) {
                log.error("Invalid event-cancelled payload — missing eventId. Payload: {}",
                        payload);
                // Acknowledge to avoid infinite retry on a malformed message
                acknowledgment.acknowledge();
                return;
            }

            UUID eventId = UUID.fromString(eventIdStr);

            log.info("Processing event-cancelled: eventId={} title={}",
                    eventId, title);

            // Cancel all active bookings for this event
            // This is transactional — all or nothing per booking
            bookingService.cancelAllBookingsForEvent(eventId);

            // Commit Kafka offset only after all bookings are processed
            acknowledgment.acknowledge();

            log.info("event-cancelled processed successfully: eventId={}", eventId);

        } catch (Exception e) {
            log.error("Failed to process event-cancelled: payload={} error={}",
                    payload, e.getMessage(), e);
            // Do NOT acknowledge — Spring Kafka will retry and eventually
            // route the record to the DLT via the configured error handler.
            throw (RuntimeException) e;
        }
    }
}