package org.stagepass.notificationservice.kafka;

import org.stagepass.notificationservice.client.EventServiceClient;
import org.stagepass.notificationservice.client.UserServiceClient;
import org.stagepass.notificationservice.dto.EventResponse;
import org.stagepass.notificationservice.dto.UserProfileDto;
import org.stagepass.notificationservice.entity.NotificationType;
import org.stagepass.notificationservice.exception.NotificationException;
import org.stagepass.notificationservice.service.EmailService;
import org.stagepass.notificationservice.service.NotificationLogService;
import org.stagepass.notificationservice.service.TemplateService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BOOKING CANCELLED CONSUMER
 *
 * Listens to booking-cancelled — user-initiated cancellation of a confirmed
 * booking.
 * Sends a cancellation confirmation with refund amount and expected timeline.
 *
 * PAYLOAD INCLUDES:
 * bookingId, eventId, userId, userEmail, userName, eventName, eventDate,
 * totalAmount (for refund display), paymentId (for reference)
 *
 * IMPORTANT:
 * This consumer handles USER-INITIATED cancellations.
 * Event-cancellation-driven cancellations are handled differently —
 * Booking Service publishes booking-cancelled for each affected booking
 * when an event is cancelled, so this consumer handles those too.
 * The template can show different messaging based on a 'reason' field in the
 * payload.
 */
@Component
public class BookingCancelledConsumer {

    private static final Logger log = LoggerFactory.getLogger(BookingCancelledConsumer.class);

    @Autowired
    private EmailService emailService;
    @Autowired
    private TemplateService templateService;
    @Autowired
    private NotificationLogService notificationLogService;
    @Autowired
    private EventServiceClient eventServiceClient;
    @Autowired
    private UserServiceClient userServiceClient;

    @KafkaListener(topics = "booking-cancelled", groupId = "${spring.kafka.consumer.group-id}", containerFactory = "manualAckListenerContainerFactory")
    public void onBookingCancelled(
            ConsumerRecord<String, Map<String, Object>> record,
            Acknowledgment acknowledgment) {

        Map<String, Object> payload = record.value();
        String resolvedEmail = "";

        try {
            String bookingId = String.valueOf(payload.get("bookingId"));
            String userIdStr = String.valueOf(payload.get("userId"));
            if (userIdStr == null || userIdStr.isBlank() || "null".equals(userIdStr)) {
                throw new NotificationException("Missing userId in booking-cancelled payload");
            }

            // Fetch user details via UserServiceClient
            String userEmail;
            String userName;
            try {
                UUID userId = UUID.fromString(userIdStr);
                UserProfileDto userProfile = userServiceClient.getUserById(userId);
                if (userProfile == null || userProfile.getEmail() == null || userProfile.getEmail().isBlank()) {
                    throw new NotificationException("Could not retrieve user profile or email for userId: " + userIdStr);
                }
                userEmail = userProfile.getEmail();
                resolvedEmail = userEmail;
                userName = userProfile.getUsername() != null ? userProfile.getUsername() : "Valued Customer";
            } catch (NotificationException e) {
                throw e;
            } catch (Exception e) {
                throw new NotificationException("Failed to fetch user details via Feign for userId=" + userIdStr + ": " + e.getMessage(), e);
            }

            String eventIdStr = String.valueOf(payload.get("eventId"));

            String eventName = "the event";
            String eventDate = "";

            if (eventIdStr != null && !eventIdStr.isBlank() && !"null".equals(eventIdStr)) {
                try {
                    UUID eventId = UUID.fromString(eventIdStr);
                    EventResponse eventResp = eventServiceClient.getEventById(eventId);
                    if (eventResp != null) {
                        eventName = eventResp.getTitle();
                        if (eventResp.getEventDate() != null) {
                            eventDate = eventResp.getEventDate().toString();
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to fetch event details via Feign for eventId={}: {}", eventIdStr, e.getMessage());
                }
            }

            if (bookingId == null || userEmail.isBlank()) {
                log.error("Invalid booking-cancelled payload");
                acknowledgment.acknowledge();
                return;
            }

            log.info("Processing booking-cancelled: bookingId={} email={}", bookingId, userEmail);

            // Idempotency check
            if (notificationLogService.alreadySent(
                    bookingId, NotificationType.BOOKING_CANCELLED)) {
                log.info("Duplicate booking-cancelled notification — skipping: bookingId={}",
                        bookingId);
                acknowledgment.acknowledge();
                return;
            }

            // Parse refund amount
            BigDecimal totalAmount = BigDecimal.ZERO;
            Object amountObj = payload.get("totalAmount");
            if (amountObj != null) {
                try {
                    totalAmount = new BigDecimal(amountObj.toString());
                } catch (NumberFormatException e) {
                    log.warn("Could not parse totalAmount: {}", amountObj);
                }
            }

            // Build template variables
            Map<String, Object> vars = new HashMap<>();
            vars.put("userName", userName);
            vars.put("eventName", eventName);
            vars.put("eventDate", eventDate);
            vars.put("refundAmount", totalAmount);
            vars.put("bookingId", bookingId);
            vars.put("paymentId", payload.get("paymentId"));
            // Refund timeline — standard for Razorpay
            vars.put("refundTimeline", "5-7 business days");

            String htmlBody = templateService.render("booking-cancelled", vars);

            emailService.sendHtml(
                    userEmail,
                    "Your StagePass booking has been cancelled",
                    htmlBody);

            notificationLogService.logSuccess(
                    bookingId, NotificationType.BOOKING_CANCELLED, userEmail);

            acknowledgment.acknowledge();

            log.info("Booking cancelled notification sent: bookingId={}", bookingId);

        } catch (NotificationException e) {
            log.error("Failed to send booking-cancelled notification: {}", e.getMessage(), e);
            notificationLogService.logFailure(
                    String.valueOf(payload.get("bookingId")),
                    NotificationType.BOOKING_CANCELLED,
                    resolvedEmail.isBlank() ? "unknown" : resolvedEmail,
                    e.getMessage());
            // Do NOT acknowledge — retry
        }
    }
}