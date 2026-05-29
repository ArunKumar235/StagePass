package org.stagepass.eventservice.kafka;

import org.stagepass.eventservice.entity.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    // Kafka topic names — match consumer topic names exactly
    private static final String TOPIC_EVENT_CREATED = "event-created";
    private static final String TOPIC_EVENT_CANCELLED = "event-cancelled";
    private static final String TOPIC_EVENT_SOLD_OUT = "event-sold-out";

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    // ── EVENT CREATED ────────────────────────────────────────────────────────

    /**
     * Published when a new event is created (status: DRAFT).
     *
     * Consumers:
     * - Notification Service: alerts users who follow this organiser
     *
     * Key = eventId ensures all events for the same event go to the same partition,
     * preserving ordering of events for that event ID.
     */
    public void publishEventCreated(Event event) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", event.getId().toString());
        payload.put("title", event.getTitle());
        payload.put("organizerId", event.getOrganizerId().toString());
        payload.put("venueId", event.getVenue() != null ? event.getVenue().getId().toString() : null);
        payload.put("eventDate", event.getEventDate() != null ? event.getEventDate().toString() : null);
        payload.put("category", event.getCategory());
        payload.put("occurredAt", Instant.now().toString());

        send(TOPIC_EVENT_CREATED, event.getId().toString(), payload);
    }

    // ── EVENT CANCELLED ──────────────────────────────────────────────────────

    /**
     * Published when an event is cancelled (admin action).
     *
     * Consumers:
     * - Booking Service: auto-cancels all active bookings for this event
     * - Notification Service: sends cancellation emails to all ticket holders
     *
     * This is the most critical event — make sure it's published reliably.
     * Consider using the Outbox pattern for this if payment refunds are involved.
     */
    public void publishEventCancelled(Event event) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", event.getId().toString());
        payload.put("title", event.getTitle());
        payload.put("eventDate", event.getEventDate() != null ? event.getEventDate().toString() : null);
        payload.put("occurredAt", Instant.now().toString());
        payload.put("organizerId", event.getOrganizerId());

        send(TOPIC_EVENT_CANCELLED, event.getId().toString(), payload);
    }

    // ── EVENT SOLD OUT ───────────────────────────────────────────────────────

    /**
     * Published when the last available seat is booked.
     *
     * Consumers:
     * - Notification Service: notifies users that the event is sold out
     */
    public void publishEventSoldOut(UUID eventId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", eventId.toString());
        payload.put("occurredAt", Instant.now().toString());

        send(TOPIC_EVENT_SOLD_OUT, eventId.toString(), payload);
    }

    // ── PRIVATE: SEND WITH ASYNC CALLBACK ────────────────────────────────────

    /**
     * Sends a Kafka message asynchronously.
     *
     * Uses CompletableFuture callback to log success or failure.
     * Failure is logged but not thrown — Kafka has its own retry mechanism
     * (configured via spring.kafka.producer.retries in application.yml).
     *
     * For critical events (event-cancelled), consider implementing the Outbox
     * pattern:
     * write the event to a DB table first, then a separate process publishes to
     * Kafka.
     * This guarantees the event is never lost even if Kafka is temporarily down.
     */
    private void send(String topic, String key, Object payload) {
        CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(topic, key, payload);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish Kafka event: topic={} key={} error={}",
                        topic, key, ex.getMessage());
                // In production: write to outbox table or dead-letter topic for retry
            } else {
                log.debug("Kafka event published: topic={} key={} partition={} offset={}",
                        topic, key,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
