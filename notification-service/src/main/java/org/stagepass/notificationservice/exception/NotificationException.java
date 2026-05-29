package org.stagepass.notificationservice.exception;

/**
 * Wraps all email-sending and PDF-generation failures.
 *
 * Thrown by:
 *   - EmailService when JavaMailSender throws MailException
 *   - TicketPdfService when iText PDF generation fails
 *   - TemplateService when Thymeleaf rendering fails
 *
 * Behaviour inside a Kafka consumer:
 *   - Consumer catches this and does NOT acknowledge the Kafka offset.
 *   - Kafka redelivers the message after the configured backoff interval.
 *   - After max retries (see KafkaConsumerConfig), the message goes to the DLT.
 *
 * This ensures no notification is silently dropped due to transient SMTP
 * or PDF errors — a temporarily down mail server results in retries,
 * while permanently malformed payloads go to the DLT for manual inspection.
 */
public class NotificationException extends RuntimeException {

    public NotificationException(String message) {
        super(message);
    }

    public NotificationException(String message, Throwable cause) {
        super(message, cause);
    }
}