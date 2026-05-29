package org.stagepass.notificationservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.stagepass.notificationservice.entity.NotificationLog;
import org.stagepass.notificationservice.entity.NotificationStatus;
import org.stagepass.notificationservice.entity.NotificationType;

import java.util.List;
import java.util.UUID;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    boolean existsByReferenceIdAndNotificationTypeAndStatus(String referenceId, NotificationType type, NotificationStatus notificationStatus);

    List<NotificationLog> findByStatus(NotificationStatus status);

    List<NotificationLog> findByRecipientEmail(String email);

}
