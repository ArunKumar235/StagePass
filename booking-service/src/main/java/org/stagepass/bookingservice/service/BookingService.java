package org.stagepass.bookingservice.service;

import org.stagepass.bookingservice.client.EventServiceClient;
import org.stagepass.bookingservice.client.PaymentServiceClient;
import org.stagepass.bookingservice.dto.*;
import org.stagepass.bookingservice.entity.Booking;
import org.stagepass.bookingservice.entity.BookingItem;
import org.stagepass.bookingservice.entity.BookingStatus;
import org.stagepass.bookingservice.exception.BookingNotFoundException;
import org.stagepass.bookingservice.exception.SeatAlreadyLockedException;
import org.stagepass.bookingservice.kafka.BookingEventPublisher;
import org.stagepass.bookingservice.repository.BookingRepository;
import org.stagepass.bookingservice.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * BOOKING SERVICE — THE SAGA ORCHESTRATOR
 *
 * The most complex class in StagePass. Implements the Choreography Saga pattern
 * to coordinate seat locking, payment, and confirmation across three services
 * without a distributed transaction.
 *
 * ── THE BOOKING SAGA ────────────────────────────────────────────────────────
 *
 * HAPPY PATH:
 * 1. Validate seats are AVAILABLE (call Event Service)
 * 2. Lock seats in Redis (TTL: 10 min) ← compensate: releaseLocks()
 * 3. Persist Booking as PENDING
 * 4. Charge payment (call Payment Service) ← compensate: refund()
 * 5. Update Booking to CONFIRMED
 * 6. Publish booking-confirmed to Kafka
 * → Event Service: mark seats BOOKED
 * → Notification Service: send ticket email
 *
 * FAILURE PATHS (compensating transactions):
 * Step 1 fails → seats unavailable → 409, nothing to compensate
 * Step 2 fails → lock failed → release acquired locks → 409
 * Step 3 fails → DB error → release all locks → 500
 * Step 4 fails → payment declined → release all locks → update to FAILED
 * → publish booking-failed
 * Step 5 fails → DB error after payment → manually reconcile (edge case)
 *
 * This is a Saga with compensating transactions — NOT a 2-phase commit.
 * Each step either succeeds or triggers a compensating action to undo the
 * previous steps. No distributed lock held across multiple services.
 *
 * ── WHY NOT 2PC ─────────────────────────────────────────────────────────────
 * 2-phase commit requires all participating services to hold locks until the
 * coordinator decides commit/rollback. Under flash sale load with thousands of
 * concurrent bookings, this causes deadlocks and cascading failures.
 * The Saga pattern sacrifices atomicity for availability — each step is
 * independently committed and compensating actions handle failures.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    @Value("${stagepass.booking.seat-lock-ttl-seconds:600}")
    private long seatLockTtlSeconds;

    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private SeatLockService seatLockService;
    @Autowired
    private EventServiceClient eventServiceClient;
    @Autowired
    private PaymentServiceClient paymentServiceClient;
    @Autowired
    private BookingEventPublisher bookingEventPublisher;
    @Autowired
    private UserContext userContext;
    @Autowired
    private TransactionTemplate transactionTemplate;

    // ── INITIATE BOOKING (THE SAGA) ──────────────────────────────────────────

    /**
     * Initiates the booking Saga.
     *
     * Orchestrates: seat validation → seat locking → DB persist → payment → confirm
     * Each step has a compensating action if it fails.
     *
     * @param request CreateBookingRequest with eventId, seatIds, paymentMethod,
     *                paymentToken
     * @return BookingResponse with bookingId, status, totalAmount, expiresAt
     */
    public BookingResponse initiateBooking(CreateBookingRequest request) {
        String userId = userContext.getUserId();
        UUID userUUID = UUID.fromString(userId);

        log.info("Booking initiated: userId={} eventId={} seatCount={}",
                userId, request.eventId(), request.seatIds().size());

        // ── STEP 1: VALIDATE SEATS ────────────────────────────────────────
        // Call Event Service to check all seats are AVAILABLE and get total price.
        // Authoritative price comes from Event Service — never trust client-sent price.
        SeatValidationResponse validation = eventServiceClient
                .validateSeats(request.eventId(), request.seatIds());

        if (!validation.availableAll()) {
            log.warn("Seat validation failed: unavailableSeats={} userId={}",
                    validation.unavailableSeatIds(), userId);
            throw new SeatAlreadyLockedException(validation.unavailableSeatIds());
        }

        BigDecimal totalAmount = validation.totalPrice();
        LocalDateTime eventDate = validation.eventDate() != null
                ? validation.eventDate().atStartOfDay()
                : null;

        // ── STEP 2: ACQUIRE REDIS SEAT LOCKS ─────────────────────────────
        // lockSeats() rolls back all acquired locks automatically if any fail.
        // Returns false if any seat was grabbed between Step 1 and Step 2
        // (the window between validation and locking — TOCTOU race condition).
        boolean locked = seatLockService.lockSeats(request.seatIds(), userUUID);

        if (!locked) {
            log.warn("Seat lock failed (race condition): userId={} seatIds={}",
                    userId, request.seatIds());
            // Re-validate to find out which specific seats are now unavailable
            throw new SeatAlreadyLockedException(request.seatIds());
        }

        // ── STEP 3: PERSIST BOOKING AS PENDING ───────────────────────────
        Booking booking;
        try {
            booking = transactionTemplate
                    .execute(status -> createPendingBooking(request, userUUID, totalAmount, validation, eventDate));
        } catch (Exception e) {
            // DB failed — compensate by releasing all seat locks
            log.error("Failed to persist booking: userId={} error={}", userId, e.getMessage());
            seatLockService.releaseLocks(request.seatIds(), userUUID);
            throw new RuntimeException("Failed to create booking. Please try again.", e);
        }

        // ── STEP 4: CHARGE PAYMENT ────────────────────────────────────────
        // bookingId is used as idempotency key — safe to retry on timeout.
        // NOTE: Payment Service returns HTTP 200 for both SUCCESS and FAILED
        // outcomes. The caller must inspect the response body `status` field
        // to determine success vs decline. Exceptions are only thrown for
        // infrastructure errors (network, timeouts, 5xx, etc.).
        ChargeRequest chargeRequest = new ChargeRequest(
                booking.getId(),
                totalAmount,
                "INR",
                request.paymentMethod(),
                request.paymentToken(),
                userUUID);

        ChargeResponse chargeResponse;
        try {
            chargeResponse = paymentServiceClient.charge(chargeRequest);
        } catch (Exception e) {
            // Infrastructure error when calling Payment Service — compensate
            log.error("Payment Service call failed (infrastructure): bookingId={} error={}",
                    booking.getId(), e.getMessage());
            compensate(booking, request.seatIds(), userUUID, "Payment Service unreachable");
            throw new RuntimeException("Payment processing failed due to infrastructure error. Please try again.", e);
        }

        // Defensive: if client returned null (shouldn't happen for a healthy
        // Payment Service that always returns a body) treat as infrastructure
        // failure and compensate.
        if (chargeResponse == null) {
            log.error("Payment Service returned null response: bookingId={}", booking.getId());
            compensate(booking, request.seatIds(), userUUID, "No response from Payment Service");
            throw new RuntimeException("Payment processing failed. No response from Payment Service.");
        }

        // ── STEP 5: CONFIRM OR COMPENSATE ────────────────────────────────
        if ("SUCCESS".equals(chargeResponse.status())) {
            return confirmBooking(booking, chargeResponse);
        } else {
            compensate(booking, request.seatIds(), userUUID,
                    chargeResponse.failureReason());
            throw new RuntimeException("Payment failed: " +
                    chargeResponse.failureReason());
        }
    }

    // ── GET BOOKING BY ID ─────────────────────────────────────────────────────

    /**
     * Returns a booking — but only if it belongs to the requesting user.
     * Returns 404 (not 403) even on ownership violation — avoids revealing
     * that the bookingId exists for another user.
     */
    @Transactional(readOnly = true)
    public BookingResponse getBookingById(UUID bookingId) {
        UUID userId = UUID.fromString(userContext.getUserId());

        Booking booking = bookingRepository
                .findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        return toResponse(booking);
    }

    // ── GET MY BOOKINGS ───────────────────────────────────────────────────────

    /**
     * Returns all bookings for the authenticated user, newest first.
     */
    @Transactional(readOnly = true)
    public List<BookingResponse> getMyBookings() {
        UUID userId = UUID.fromString(userContext.getUserId());

        return bookingRepository
                .findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // ── GET BOOKINGS BY EVENT (ADMIN) ─────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<BookingResponse> getBookingsByEvent(UUID eventId) {
        return bookingRepository.findByEventId(eventId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // ── BULK CANCEL FOR EVENT (called by EventCancellationConsumer) ───────────

    /**
     * Cancels all PENDING and CONFIRMED bookings for a cancelled event.
     * Called internally by EventCancellationConsumer — not a REST endpoint.
     *
     * For PENDING bookings: release Redis locks + mark FAILED
     * For CONFIRMED bookings: publish booking-cancelled so Payment Service
     * initiates refunds and Notification Service alerts users
     */
    @Transactional
    public void cancelAllBookingsForEvent(UUID eventId) {
        List<Booking> activeBookings = bookingRepository
                .findByEventIdAndStatusIn(eventId,
                        List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED));

        log.info("Cancelling {} bookings for cancelled eventId={}",
                activeBookings.size(), eventId);

        for (Booking booking : activeBookings) {
            if (booking.getStatus() == BookingStatus.PENDING) {
                // Release Redis locks for pending bookings
                List<UUID> seatIds = booking.getItems().stream()
                        .map(BookingItem::getSeatId)
                        .collect(Collectors.toList());
                seatLockService.releaseLocks(seatIds,
                        booking.getUserId());
                booking.setStatus(BookingStatus.FAILED);
            } else {
                // CONFIRMED — publish cancelled event for refund + notification
                booking.setStatus(BookingStatus.CANCELLED);
                booking.setCancelledAt(LocalDateTime.now());
                bookingEventPublisher.publishBookingCancelled(booking);
            }
            bookingRepository.save(booking);
        }
    }

    // ── PRIVATE: CONFIRM ──────────────────────────────────────────────────────

    /**
     * Step 5 (success path): Update booking to CONFIRMED and publish Kafka event.
     */
    private BookingResponse confirmBooking(Booking booking, ChargeResponse charge) {
        Booking saved = transactionTemplate.execute(status -> {
            Booking b = bookingRepository.findByIdWithItems(booking.getId())
                    .orElseThrow(() -> new BookingNotFoundException(booking.getId()));
            b.setStatus(BookingStatus.CONFIRMED);
            b.setPaymentId(charge.paymentId());
            b.setConfirmedAt(LocalDateTime.now());
            return bookingRepository.save(b);
        });

        // Publish booking-confirmed:
        // → Event Service: mark seats as BOOKED
        // → Notification Service: send ticket email + PDF
        bookingEventPublisher.publishBookingConfirmed(saved);

        log.info("Booking confirmed: bookingId={} userId={} paymentId={} amount={}",
                saved.getId(), saved.getUserId(),
                charge.paymentId(), saved.getTotalAmount());

        return toResponse(saved);
    }

    // ── PRIVATE: COMPENSATE ───────────────────────────────────────────────────

    /**
     * Compensating transaction: undo everything done so far.
     * Called when payment fails or Payment Service is unreachable.
     *
     * 1. Release all Redis seat locks
     * 2. Update Booking status to FAILED
     * 3. Publish booking-failed to Kafka
     * → Event Service: release seats back to AVAILABLE
     */
    private void compensate(Booking booking, List<UUID> seatIds,
            UUID userId, String reason) {
        log.warn("Compensating booking: bookingId={} userId={} reason={}",
                booking.getId(), userId, reason);

        // Release Redis locks
        seatLockService.releaseLocks(seatIds, userId);

        // Update DB and load fully initialized entity
        Booking saved = transactionTemplate.execute(status -> {
            Booking b = bookingRepository.findByIdWithItems(booking.getId())
                    .orElseThrow(() -> new BookingNotFoundException(booking.getId()));
            b.setStatus(BookingStatus.FAILED);
            return bookingRepository.save(b);
        });

        // Publish failure event using fully initialized entity to avoid
        // LazyInitializationException
        bookingEventPublisher.publishBookingFailed(saved, reason);
    }

    // ── PRIVATE: CREATE PENDING BOOKING ───────────────────────────────────────

    /**
     * Builds and persists the Booking entity with PENDING status.
     * Denormalises seat details (row, number, tier, price) from the
     * SeatValidationResponse into BookingItem rows.
     *
     * Denormalisation is intentional — never rely on cross-service lookups
     * to render a ticket. If Event Service changes seat data later, the
     * booked ticket should reflect what was purchased, not the current state.
     */
    private Booking createPendingBooking(CreateBookingRequest request,
            UUID userId,
            BigDecimal totalAmount,
            SeatValidationResponse validation,
            LocalDateTime eventDate) {
        Booking booking = new Booking();
        booking.setUserId(userId);
        booking.setEventId(request.eventId());
        booking.setStatus(BookingStatus.PENDING);
        booking.setTotalAmount(totalAmount);
        booking.setPaymentMethod(request.paymentMethod());
        booking.setCreatedAt(LocalDateTime.now());
        booking.setEventDate(eventDate);
        // expiresAt matches Redis TTL — used by ExpiredBookingScheduler
        booking.setExpiresAt(LocalDateTime.now()
                .plusSeconds(seatLockTtlSeconds));

        // Build one BookingItem per seat
        List<BookingItem> items = new ArrayList<>();
        for (SeatValidationResponse.SeatDetail detail : validation.seatDetails()) {
            BookingItem item = new BookingItem();
            item.setSeatId(detail.seatId());
            item.setSectionId(detail.sectionId());
            item.setRowLabel(detail.rowLabel());
            item.setBooking(booking); // set parent reference for JPA
            item.setSeatNumber(detail.seatNumber());
            item.setTier(detail.tier());
            item.setPrice(detail.price());
            item.setSectionName(detail.sectionName());
            items.add(item);
        }
        booking.setItems(items);

        return bookingRepository.save(booking);
    }

    // ── PRIVATE: MAP TO RESPONSE ──────────────────────────────────────────────

    private BookingResponse toResponse(Booking booking) {
        List<BookingItemResponse> itemResponses = null;

        if (booking.getItems() != null) {
            itemResponses = booking.getItems().stream().map(item -> BookingItemResponse.builder()
                    .seatId(item.getSeatId())
                    .sectionId(item.getSectionId())
                    .rowLabel(item.getRowLabel())
                    .seatNumber(item.getSeatNumber())
                    .tier(item.getTier())
                    .sectionName(item.getSectionName())
                    .price(item.getPrice())
                    .build()).collect(Collectors.toList());
        }

        return BookingResponse.builder()
                .bookingId(booking.getId())
                .eventId(booking.getEventId())
                .userId(booking.getUserId())
                .status(booking.getStatus())
                .items(itemResponses)
                .totalAmount(booking.getTotalAmount())
                .paymentId(booking.getPaymentId())
                .paymentMethod(booking.getPaymentMethod())
                .createdAt(booking.getCreatedAt())
                .expiresAt(booking.getExpiresAt())
                .confirmedAt(booking.getConfirmedAt())
                .cancelledAt(booking.getCancelledAt())
                .build();
    }

}