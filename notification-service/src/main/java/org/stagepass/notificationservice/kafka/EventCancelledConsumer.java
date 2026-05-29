package org.stagepass.notificationservice.kafka;

import org.stagepass.notificationservice.entity.NotificationType;
import org.stagepass.notificationservice.exception.NotificationException;
import org.stagepass.notificationservice.service.EmailService;
import org.stagepass.notificationservice.service.NotificationLogService;
import org.stagepass.notificationservice.service.TemplateService;
import org.stagepass.notificationservice.client.UserServiceClient;
import org.stagepass.notificationservice.dto.UserProfileDto;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * EVENT CANCELLED CONSUMER
 *
 * Listens to event-cancelled — published by Event Service when an admin cancels an event.
 *
 * SCOPE:
 * This consumer sends the event-cancellation alert to the ORGANISER.
 * Individual ticket holders are notified via BookingCancelledConsumer —
 * the Booking Service publishes one booking-cancelled event per affected booking
 * when it processes the event cancellation. This gives each ticket holder a
 * personalised email with their specific refund amount.
 *
 * This consumer is responsible for:
 * - Notifying the organiser that their event has been cancelled (admin action)
 * - Optionally: sending a general announcement email if a mailing list is maintained
 *   (out of scope for V1 — would require User Service integration)
 *
 * PAYLOAD FIELDS:
 * eventId, title (event name), eventDate, organizerId
 *
 * V1 SIMPLIFICATION:
 * If the booking-cancelled payload already includes userEmail for each ticket holder,
 * and Booking Service publishes individual booking-cancelled events,
 * this consumer only needs to alert the organiser. The ticket holders get their
 * personalised emails via BookingCancelledConsumer.
 */
@Component
public class EventCancelledConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventCancelledConsumer.class);

    @Autowired private EmailService           emailService;
    @Autowired private TemplateService        templateService;
    @Autowired private NotificationLogService notificationLogService;
    @Autowired private UserServiceClient      userServiceClient;

    @KafkaListener(
            topics = "event-cancelled",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "manualAckListenerContainerFactory"
    )
    public void onEventCancelled(
            ConsumerRecord<String, Map<String, Object>> record,
            Acknowledgment acknowledgment) {

        Map<String, Object> payload = record.value();

        try {
            String eventId        = String.valueOf(payload.get("eventId"));
            String eventName      = String.valueOf(payload.getOrDefault("title", "the event"));
            String eventDate      = String.valueOf(payload.getOrDefault("eventDate", ""));
            String organizerIdStr = String.valueOf(payload.getOrDefault("organizerId", ""));

            String organizerEmail = "";
            String organizerName  = "Organiser";

            if (organizerIdStr != null && !organizerIdStr.isBlank() && !"null".equals(organizerIdStr)) {
                try {
                    UUID organizerId = UUID.fromString(organizerIdStr);
                    UserProfileDto userProfile = userServiceClient.getUserById(organizerId);
                    if (userProfile != null) {
                        organizerEmail = userProfile.getEmail();
                        organizerName  = userProfile.getUsername();
                    }
                } catch (Exception e) {
                    log.error("Failed to fetch organizer details via Feign for organizerId={}: {}", organizerIdStr, e.getMessage());
                }
            }

            if (eventId == null) {
                log.error("Invalid event-cancelled payload — missing eventId");
                acknowledgment.acknowledge();
                return;
            }

            log.info("Processing event-cancelled: eventId={} organizerEmail={}",
                    eventId, organizerEmail);

            // Idempotency check — use eventId as reference
            if (notificationLogService.alreadySent(
                    eventId, NotificationType.EVENT_CANCELLED)) {
                log.info("Duplicate event-cancelled notification — skipping: eventId={}",
                        eventId);
                acknowledgment.acknowledge();
                return;
            }

            // Notify organiser if email is available
            if (!organizerEmail.isBlank() && !"null".equals(organizerEmail)) {
                Map<String, Object> vars = new HashMap<>();
                vars.put("organizerName", organizerName);
                vars.put("eventName",     eventName);
                vars.put("eventDate",     eventDate);
                vars.put("eventId",       eventId);

                String htmlBody = templateService.render("event-cancelled", vars);

                emailService.sendHtml(
                        organizerEmail,
                        "Your StagePass event has been cancelled: " + eventName,
                        htmlBody
                );

                notificationLogService.logSuccess(
                        eventId, NotificationType.EVENT_CANCELLED, organizerEmail);
            } else {
                log.warn("event-cancelled: no organizer email in payload for eventId={}",
                        eventId);
                // Still log so idempotency check passes — don't retry
                notificationLogService.logSuccess(
                        eventId, NotificationType.EVENT_CANCELLED, "no-email");
            }

            acknowledgment.acknowledge();

            log.info("Event cancelled notification processed: eventId={}", eventId);

        } catch (NotificationException e) {
            log.error("Failed to send event-cancelled notification: {}", e.getMessage(), e);
            notificationLogService.logFailure(
                    String.valueOf(payload.get("eventId")),
                    NotificationType.EVENT_CANCELLED,
                    String.valueOf(payload.getOrDefault("organizerEmail", "no-email")),
                    e.getMessage()
            );
            // Do NOT acknowledge — retry
        }
    }
}