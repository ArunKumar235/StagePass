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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BOOKING FAILED CONSUMER
 *
 * Listens to booking-failed — payment declined or Saga compensation triggered.
 * Sends a sympathetic failure alert email with actionable guidance.
 *
 * TONE GUIDELINE:
 * Never expose raw technical error codes (BAD_REQUEST_ERROR, GATEWAY_ERROR) to
 * users.
 * Map them to plain English in mapFailureReason() before passing to the
 * template.
 *
 * "BAD_REQUEST_ERROR" → "Your card was declined. Please check your card details
 * or try a different payment method."
 * "GATEWAY_ERROR" → "Your bank could not process this payment. Please try again
 * or contact your bank."
 * "SERVER_ERROR" → "A temporary error occurred. Please try again in a few
 * minutes."
 * null / unknown → "Your payment could not be processed. Please try again."
 */
@Component
public class BookingFailedConsumer {

    private static final Logger log = LoggerFactory.getLogger(BookingFailedConsumer.class);

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

    @KafkaListener(topics = "booking-failed", groupId = "${spring.kafka.consumer.group-id}", containerFactory = "manualAckListenerContainerFactory")
    public void onBookingFailed(
            ConsumerRecord<String, Map<String, Object>> record,
            Acknowledgment acknowledgment) {

        Map<String, Object> payload = record.value();
        String resolvedEmail = "";

        try {
            String bookingId = String.valueOf(payload.get("bookingId"));
            String userIdStr = String.valueOf(payload.get("userId"));
            if (userIdStr == null || userIdStr.isBlank() || "null".equals(userIdStr)) {
                throw new NotificationException("Missing userId in booking-failed payload");
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
            String rawReason = String.valueOf(payload.getOrDefault("reason", ""));

            String eventName = "the event";

            if (eventIdStr != null && !eventIdStr.isBlank() && !"null".equals(eventIdStr)) {
                try {
                    UUID eventId = UUID.fromString(eventIdStr);
                    EventResponse eventResp = eventServiceClient.getEventById(eventId);
                    if (eventResp != null) {
                        eventName = eventResp.getTitle();
                    }
                } catch (Exception e) {
                    log.error("Failed to fetch event details via Feign for eventId={}: {}", eventIdStr, e.getMessage());
                }
            }

            if (bookingId == null || userEmail.isBlank()) {
                log.error("Invalid booking-failed payload — missing bookingId or email");
                acknowledgment.acknowledge();
                return;
            }

            log.info("Processing booking-failed: bookingId={} email={}", bookingId, userEmail);

            // Idempotency check
            if (notificationLogService.alreadySent(
                    bookingId, NotificationType.BOOKING_FAILED)) {
                log.info("Duplicate booking-failed notification — skipping: bookingId={}", bookingId);
                acknowledgment.acknowledge();
                return;
            }

            // Map raw reason to user-friendly message
            String friendlyReason = mapFailureReason(rawReason);

            // Build template variables
            Map<String, Object> vars = new HashMap<>();
            vars.put("userName", userName);
            vars.put("eventName", eventName);
            vars.put("failureReason", friendlyReason);
            vars.put("bookingId", bookingId);

            String htmlBody = templateService.render("booking-failed", vars);

            emailService.sendHtml(
                    userEmail,
                    "We couldn't complete your StagePass booking",
                    htmlBody);

            notificationLogService.logSuccess(
                    bookingId, NotificationType.BOOKING_FAILED, userEmail);

            acknowledgment.acknowledge();

            log.info("Booking failed notification sent: bookingId={}", bookingId);

        } catch (NotificationException e) {
            log.error("Failed to send booking-failed notification: error={}", e.getMessage(), e);
            notificationLogService.logFailure(
                    String.valueOf(payload.get("bookingId")),
                    NotificationType.BOOKING_FAILED,
                    resolvedEmail.isBlank() ? "unknown" : resolvedEmail,
                    e.getMessage());
            // Do NOT acknowledge — retry
        }
    }

    /**
     * Maps Razorpay/internal error codes to user-friendly messages.
     * Never expose raw error codes in emails.
     */
    private String mapFailureReason(String rawReason) {
        if (rawReason == null || rawReason.isBlank() || "null".equals(rawReason)) {
            return "Your payment could not be processed. Please try again.";
        }
        if (rawReason.contains("BAD_REQUEST_ERROR") ||
                rawReason.toLowerCase().contains("card")) {
            return "Your card was declined. Please check your card details " +
                    "or try a different payment method.";
        }
        if (rawReason.contains("GATEWAY_ERROR")) {
            return "Your bank could not process this payment. " +
                    "Please try again or contact your bank.";
        }
        if (rawReason.contains("SERVER_ERROR") ||
                rawReason.contains("unavailable")) {
            return "A temporary error occurred with our payment system. " +
                    "Please try again in a few minutes.";
        }
        if (rawReason.contains("expired")) {
            return "Your booking session expired before payment was completed. " +
                    "Please start a new booking.";
        }
        return "Your payment could not be processed. Please try again " +
                "or contact support if the issue persists.";
    }
}