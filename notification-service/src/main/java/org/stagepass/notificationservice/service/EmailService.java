package org.stagepass.notificationservice.service;

import org.springframework.core.io.ByteArrayResource;
import org.stagepass.notificationservice.exception.NotificationException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Autowired
    private JavaMailSender mailSender;

    @Value("${stagepass.notification.from-email}")
    private String fromEmail;

    @Value("${stagepass.notification.from-name}")
    private String fromName;

    public void sendHtml(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromEmail, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("Email sent: to={} subject={}", to, subject);
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            log.error("Failed to send email: to={} error={}", to, e.getMessage());
            throw new NotificationException("Email send failed: " + e.getMessage(), e);
        }
    }

    public void sendWithAttachment(String to, String subject, String htmlBody,
            byte[] attachment, String attachmentName) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            // multipart=true required for attachments
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);

            if (attachment != null && attachment.length > 0) {
                helper.addAttachment(
                        attachmentName,
                        new ByteArrayResource(attachment),
                        "application/pdf");
            }

            mailSender.send(message);
            log.info("Email with attachment sent: to={} attachment={}", to, attachmentName);
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            log.error("Failed to send email with attachment: to={} error={}", to, e.getMessage());
            throw new NotificationException("Email send failed: " + e.getMessage(), e);
        }
    }
}
