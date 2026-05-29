package org.stagepass.notificationservice.kafka;

import org.stagepass.notificationservice.entity.NotificationType;
import org.stagepass.notificationservice.exception.NotificationException;
import org.stagepass.notificationservice.service.EmailService;
import org.stagepass.notificationservice.service.NotificationLogService;
import org.stagepass.notificationservice.service.TemplateService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * USER REGISTERED CONSUMER
 *
 * Listens to user-registered — published by User Service after a new
 * registration.
 * Sends a welcome email with onboarding guidance.
 *
 * PAYLOAD FIELDS:
 * userId, email, username (name), occurredAt
 *
 * IDEMPOTENCY KEY: userId (not email — user might change their email in future)
 *
 * SIMPLEST CONSUMER:
 * No PDF attachment, no complex data parsing, no seat details.
 * Just render a welcome template with the user's name and send.
 * Good starting point if you're building consumers in order.
 */
@Component
public class UserRegisteredConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserRegisteredConsumer.class);

    @Autowired
    private EmailService emailService;
    @Autowired
    private TemplateService templateService;
    @Autowired
    private NotificationLogService notificationLogService;

    @Value("${stagepass.notification.app-url}")
    private String appUrl;

    @KafkaListener(topics = "user-registered", groupId = "${spring.kafka.consumer.group-id}", containerFactory = "manualAckListenerContainerFactory")
    public void onUserRegistered(
            ConsumerRecord<String, Map<String, Object>> record,
            Acknowledgment acknowledgment) {

        Map<String, Object> payload = record.value();

        try {
            String userId = String.valueOf(payload.get("userId"));
            String email = String.valueOf(payload.getOrDefault("email", ""));
            String username = String.valueOf(payload.getOrDefault("username", "there"));

            if (userId == null || email.isBlank()) {
                log.error("Invalid user-registered payload — missing userId or email");
                acknowledgment.acknowledge();
                return;
            }

            log.info("Processing user-registered: userId={} email={}", userId, email);

            // Idempotency check — use userId as reference
            if (notificationLogService.alreadySent(
                    userId, NotificationType.USER_WELCOME)) {
                log.info("Duplicate user-registered notification — skipping: userId={}", userId);
                acknowledgment.acknowledge();
                return;
            }

            // Build template variables
            Map<String, Object> vars = new HashMap<>();
            vars.put("userName", username);
            vars.put("browseUrl", appUrl + "/events");
            vars.put("appUrl", appUrl);

            String htmlBody = templateService.render("welcome", vars);

            emailService.sendHtml(
                    email,
                    "Welcome to StagePass 🎉 — Your world of live events starts here",
                    htmlBody);

            notificationLogService.logSuccess(
                    userId, NotificationType.USER_WELCOME, email);

            acknowledgment.acknowledge();

            log.info("Welcome email sent: userId={} email={}", userId, email);

        } catch (NotificationException e) {
            log.error("Failed to send welcome email: userId={} error={}",
                    payload.get("userId"), e.getMessage(), e);
            notificationLogService.logFailure(
                    String.valueOf(payload.get("userId")),
                    NotificationType.USER_WELCOME,
                    String.valueOf(payload.getOrDefault("email", "")),
                    e.getMessage());
            // Do NOT acknowledge — retry
        }
    }
}