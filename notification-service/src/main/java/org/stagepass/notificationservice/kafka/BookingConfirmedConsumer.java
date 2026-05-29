package org.stagepass.notificationservice.kafka;

import org.stagepass.notificationservice.client.EventServiceClient;
import org.stagepass.notificationservice.client.UserServiceClient;
import org.stagepass.notificationservice.dto.EventResponse;
import org.stagepass.notificationservice.dto.UserProfileDto;
import org.stagepass.notificationservice.dto.BookingConfirmedEvent;
import org.stagepass.notificationservice.dto.SeatDetail;
import org.stagepass.notificationservice.entity.NotificationType;
import org.stagepass.notificationservice.exception.NotificationException;
import org.stagepass.notificationservice.service.EmailService;
import org.stagepass.notificationservice.service.NotificationLogService;
import org.stagepass.notificationservice.service.TemplateService;
import org.stagepass.notificationservice.service.TicketPdfService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BOOKING CONFIRMED CONSUMER
 *
 * The most important Kafka consumer in StagePass.
 * Triggered when Booking Service confirms a payment and publishes
 * booking-confirmed.
 *
 * FLOW:
 * 1. Parse payload → BookingConfirmedEvent
 * 2. Idempotency check — already sent for this bookingId? Acknowledge and
 * return.
 * 3. Build Thymeleaf template variables
 * 4. Render booking-confirmed.html → HTML string
 * 5. Generate PDF ticket → byte[]
 * 6. Send HTML email with PDF attachment
 * 7. Log delivery to notification_logs
 * 8. Acknowledge Kafka offset
 *
 * FAILURE HANDLING:
 * If email send fails (SMTP down) or PDF generation fails:
 * - NotificationException is thrown
 * - Kafka offset NOT acknowledged
 * - KafkaConsumerConfig's error handler retries 3 times with 2s gap
 * - After 3 failures → message goes to booking-confirmed.DLT
 *
 * IDEMPOTENCY:
 * Kafka delivers at-least-once. If this consumer crashes after sending
 * the email but before acknowledging, Kafka redelivers.
 * NotificationLogService.alreadySent() detects the duplicate using the
 * notification_logs table and skips resending. The user gets exactly one email.
 */
@Component
public class BookingConfirmedConsumer {

        private static final Logger log = LoggerFactory.getLogger(BookingConfirmedConsumer.class);

        @Autowired
        private EmailService emailService;
        @Autowired
        private TemplateService templateService;
        @Autowired
        private TicketPdfService ticketPdfService;
        @Autowired
        private NotificationLogService notificationLogService;
        @Autowired
        private EventServiceClient eventServiceClient;
        @Autowired
        private UserServiceClient userServiceClient;

        @Value("${stagepass.notification.from-name}")
        private String fromName;

        @KafkaListener(topics = "booking-confirmed", groupId = "${spring.kafka.consumer.group-id}", containerFactory = "manualAckListenerContainerFactory")
        public void onBookingConfirmed(
                        ConsumerRecord<String, Map<String, Object>> record,
                        Acknowledgment acknowledgment) {

                Map<String, Object> payload = record.value();
                BookingConfirmedEvent event = null;

                try {
                        // ── STEP 1: Parse payload ──────────────────────────────────────
                        event = parsePayload(payload);

                        if (event == null || event.bookingId() == null) {
                                log.error("Invalid booking-confirmed payload — missing bookingId. " +
                                                "Payload: {}", payload);
                                acknowledgment.acknowledge(); // Bad payload — don't retry
                                return;
                        }

                        log.info("Processing booking-confirmed: bookingId={} userId={} email={}",
                                        event.bookingId(), event.userId(), event.userEmail());

                        // ── STEP 2: Idempotency check ──────────────────────────────────
                        if (notificationLogService.alreadySent(
                                        event.bookingId(), NotificationType.BOOKING_CONFIRMED)) {
                                log.info("Duplicate booking-confirmed — already sent: bookingId={}",
                                                event.bookingId());
                                acknowledgment.acknowledge();
                                return;
                        }

                        // ── STEP 3: Build template variables ───────────────────────────
                        Map<String, Object> templateVars = buildTemplateVars(event);

                        // ── STEP 4: Render HTML template ───────────────────────────────
                        String htmlBody = templateService.render("booking-confirmed", templateVars);

                        // ── STEP 5: Generate PDF ticket ────────────────────────────────
                        byte[] ticketPdf = ticketPdfService.generateTicket(event);

                        String attachmentName = String.format("stagepass-ticket-%s.pdf",
                                        event.bookingId().substring(0, 8).toUpperCase());

                        // ── STEP 6: Send email with attachment ─────────────────────────
                        String subject = String.format("Your %s tickets are confirmed! 🎵", fromName);

                        emailService.sendWithAttachment(
                                        event.userEmail(),
                                        subject,
                                        htmlBody,
                                        ticketPdf,
                                        attachmentName);

                        // ── STEP 7: Log delivery ───────────────────────────────────────
                        notificationLogService.logSuccess(
                                        event.bookingId(),
                                        NotificationType.BOOKING_CONFIRMED,
                                        event.userEmail());

                        // ── STEP 8: Acknowledge Kafka offset ───────────────────────────
                        acknowledgment.acknowledge();

                        log.info("Booking confirmed notification sent: bookingId={} email={}",
                                        event.bookingId(), event.userEmail());

                } catch (NotificationException e) {
                        log.error("Failed to send booking-confirmed notification: " +
                                        "bookingId={} error={}",
                                        payload.get("bookingId"), e.getMessage(), e);

                        // Log failure in DB for monitoring
                        notificationLogService.logFailure(
                                        String.valueOf(payload.get("bookingId")),
                                        NotificationType.BOOKING_CONFIRMED,
                                        (event != null && event.userEmail() != null) ? event.userEmail() : "unknown",
                                        e.getMessage());

                        // Do NOT acknowledge — Kafka will retry
                } catch (Exception e) {
                        log.error("Unexpected error processing booking-confirmed: {}",
                                        e.getMessage(), e);
                        // Do NOT acknowledge — let retry handle it
                }
        }

        // ── PRIVATE HELPERS ───────────────────────────────────────────────────────

        private BookingConfirmedEvent parsePayload(Map<String, Object> payload) throws NotificationException {
                try {

                        // Parse seat details list
                        Object seatsObj = payload.get("seatDetails");
                        List<SeatDetail> seats = new ArrayList<>();
                        if (seatsObj instanceof List<?> seatList) {
                                for (Object seatObj : seatList) {
                                        if (seatObj instanceof Map<?, ?> seatMap) {
                                            Object priceObj = seatMap.get("price");
                                            Object sectionNameObj = seatMap.containsKey("sectionName")
                                                            ? seatMap.get("sectionName")
                                                            : "";

                                            SeatDetail seat = SeatDetail.builder()
                                                            .seatId(String.valueOf(seatMap.get("seatId")))
                                                            .rowLabel(String.valueOf(seatMap.get("rowLabel")))
                                                            .seatNumber(Integer.parseInt(String
                                                                            .valueOf(seatMap.get("seatNumber"))))
                                                            .tier(String.valueOf(seatMap.get("tier")))
                                                            .sectionName(String.valueOf(sectionNameObj))
                                                            .price((priceObj != null)
                                                                            ? new BigDecimal(priceObj.toString())
                                                                            : null)
                                                            .build();
                                            seats.add(seat);
                                        }
                                }
                        }

                        // Parse total amount
                        Object amountObj = payload.get("totalAmount");

                        // Fetch and parse user details via UserServiceClient
                        String userIdStr = String.valueOf(payload.get("userId"));
                        if (userIdStr == null || userIdStr.isBlank() || "null".equals(userIdStr)) {
                                throw new NotificationException("Missing userId in booking-confirmed payload");
                        }

                        String userEmail;
                        String userName;
                        try {
                                UUID userId = UUID.fromString(userIdStr);
                                UserProfileDto userProfile = userServiceClient.getUserById(userId);
                                if (userProfile == null || userProfile.getEmail() == null || userProfile.getEmail().isBlank()) {
                                        throw new NotificationException("Could not retrieve user profile or email for userId: " + userIdStr);
                                }
                                userEmail = userProfile.getEmail();
                                userName = userProfile.getUsername() != null ? userProfile.getUsername() : "Valued Customer";
                        } catch (NotificationException e) {
                                throw e;
                        } catch (Exception e) {
                                throw new NotificationException("Failed to fetch user details via Feign for userId=" + userIdStr + ": " + e.getMessage(), e);
                        }

                        String eventIdStr = String.valueOf(payload.get("eventId"));
                        String eventName = "Concert";
                        String eventDate = "";
                        String venueName = "";
                        String venueAddress = "";

                        if (eventIdStr != null && !eventIdStr.isBlank() && !"null".equals(eventIdStr)) {
                                try {
                                        UUID eventId = UUID.fromString(eventIdStr);
                                        EventResponse eventResp = eventServiceClient.getEventById(eventId);
                                        if (eventResp != null) {
                                                eventName = eventResp.getTitle();
                                                if (eventResp.getEventDate() != null) {
                                                        eventDate = eventResp.getEventDate().toString();
                                                }
                                                if (eventResp.getVenue() != null) {
                                                        venueName = eventResp.getVenue().getName();
                                                        venueAddress = eventResp.getVenue().getAddress();
                                                }
                                        }
                                } catch (Exception e) {
                                        log.error("Failed to fetch event details via Feign for eventId={}: {}", eventIdStr, e.getMessage());
                                }
                        }

                        return BookingConfirmedEvent.builder()
                                        .bookingId(String.valueOf(payload.get("bookingId")))
                                        .eventId(eventIdStr)
                                        .userId(userIdStr)
                                        .userEmail(userEmail)
                                        .userName(userName)
                                        .eventName(eventName)
                                        .eventDate(eventDate)
                                        .venueName(venueName)
                                        .venueAddress(venueAddress)
                                        .paymentId(String.valueOf(payload.get("paymentId")))
                                        .seatDetails(seats)
                                        .totalAmount((amountObj != null) ? new BigDecimal(amountObj.toString()) : null)
                                        .build();

                } catch (NotificationException e) {
                        throw e;
                } catch (Exception e) {
                        log.error("Failed to parse booking-confirmed payload: {}", e.getMessage());
                        return null;
                }
        }

        private Map<String, Object> buildTemplateVars(BookingConfirmedEvent event) {
                Map<String, Object> vars = new HashMap<>();
                vars.put("userName", event.userName());
                vars.put("eventName", event.eventName());
                vars.put("eventDate", event.eventDate());
                vars.put("venueName", event.venueName());
                vars.put("venueAddress", event.venueAddress());
                vars.put("seats", event.seatDetails());
                vars.put("totalAmount", event.totalAmount());
                vars.put("bookingId", event.bookingId());
                vars.put("paymentId", event.paymentId());
                return vars;
        }
}