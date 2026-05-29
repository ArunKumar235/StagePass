package org.stagepass.notificationservice.service;

import org.stagepass.notificationservice.entity.NotificationLog;
import org.stagepass.notificationservice.entity.NotificationStatus;
import org.stagepass.notificationservice.entity.NotificationType;
import org.stagepass.notificationservice.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationLogService {

    private final NotificationLogRepository repository;

    /**
     * Idempotency guard.
     * Returns true if a DELIVERED log already exists for this referenceId + type.
     * Consumers skip sending and ack Kafka when this returns true.
     *
     * @param referenceId bookingId (for booking events), userId (for user events), eventId (for event events)
     * @param type        the notification type to check
     */
    public boolean alreadySent(String referenceId, NotificationType type) {
        boolean exists = repository.existsByReferenceIdAndNotificationTypeAndStatus(
                referenceId, type, NotificationStatus.DELIVERED
        );
        if (exists) {
            log.info("Duplicate notification skipped: referenceId={}, type={}", referenceId, type);
        }
        return exists;
    }

    /**
     * Records the outcome of a notification send attempt.
     * Called by every consumer after every send attempt (success or failure).
     *
     * @param referenceId    bookingId / userId / eventId
     * @param type           notification type
     * @param recipientEmail the email address the notification was sent to
     * @param status         DELIVERED on success, FAILED on exception
     * @param failureReason  SMTP error message on failure; null on success
     */
    public void log(String referenceId,
                    NotificationType type,
                    String recipientEmail,
                    NotificationStatus status,
                    String failureReason) {

        NotificationLog entry = NotificationLog.builder()
                .referenceId(referenceId)
                .notificationType(type)
                .recipientEmail(recipientEmail)
                .status(status)
                .failureReason(failureReason)
                .attemptCount(1)
                .sentAt(LocalDateTime.now())
                .build();

        repository.save(entry);
        log.info("Notification logged: referenceId={}, type={}, status={}", referenceId, type, status);
    }

    /**
     * Convenience overload for successful sends (no failure reason).
     */
    public void logSuccess(String referenceId, NotificationType type, String recipientEmail) {
        log(referenceId, type, recipientEmail, NotificationStatus.DELIVERED, null);
    }

    /**
     * Convenience overload for failed sends.
     */
    public void logFailure(String referenceId, NotificationType type, String recipientEmail, String reason) {
        log(referenceId, type, recipientEmail, NotificationStatus.FAILED, reason);
    }
}
