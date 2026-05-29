package org.stagepass.notificationservice.dto;

public record EmailRequest(

        String to,
        String subject,
        String htmlBody,
        byte[] attachment,
        String attachmentName

) {
}
