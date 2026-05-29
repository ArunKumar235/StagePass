package org.stagepass.notificationservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "notification_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String referenceId;

    @Enumerated(EnumType.STRING)
    private NotificationType notificationType;

    private String recipientEmail;

    @Enumerated(EnumType.STRING)
    private NotificationStatus status;

    private String failureReason;

    private int attemptCount;

    private LocalDateTime sentAt;

}
