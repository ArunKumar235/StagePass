package org.stagepass.bookingservice.scheduler;

import org.stagepass.bookingservice.entity.Booking;
import org.stagepass.bookingservice.entity.BookingItem;
import org.stagepass.bookingservice.entity.BookingStatus;
import org.stagepass.bookingservice.kafka.BookingEventPublisher;
import org.stagepass.bookingservice.repository.BookingRepository;
import org.stagepass.bookingservice.service.SeatLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * EXPIRED BOOKING SCHEDULER
 *
 * Handles the edge case where a PENDING booking exists in the DB
 * but its Redis TTL lock has already expired.
 *
 * ── WHY THIS IS NEEDED ───────────────────────────────────────────────────────
 *
 * Normal flow:
 * Redis TTL expires → seat auto-released in Redis
 * Booking Service detects payment timeout → marks booking FAILED → publishes
 * booking-failed
 *
 * Edge case (this scheduler handles):
 * 1. User initiates booking → Redis lock set, DB booking = PENDING
 * 2. User abandons checkout (closes browser, network drop)
 * 3. Redis TTL expires (10 min) → seat released in Redis automatically
 * 4. BUT DB booking still shows PENDING — Redis doesn't update the DB
 * 5. This scheduler detects orphaned PENDING bookings and cleans them up
 *
 * Without this scheduler:
 * - Stale PENDING bookings accumulate in the DB indefinitely
 * - Reports show inflated "pending" counts
 *
 * ── HOW IT WORKS ────────────────────────────────────────────────────────────
 *
 * Runs every 2 minutes (configurable).
 * Query: find all PENDING bookings where expiresAt < now
 * For each:
 * 1. Double-check Redis — is the lock really gone?
 * 2. If yes (lock expired) → mark booking FAILED → publish booking-failed
 * 3. If no (lock still active, booking not expired yet) → skip
 *
 * Step 3 is the belt-and-suspenders check. expiresAt in DB is set to
 * now + 600s at booking creation — it should align with the Redis TTL.
 * But clock skew between services could cause slight misalignment.
 * Checking Redis directly is the authoritative source of truth for lock state.
 *
 * ── IDEMPOTENCY ─────────────────────────────────────────────────────────────
 * If the scheduler runs twice before an acknowledgment (restart scenario),
 * the second run finds the booking already FAILED and skips it.
 */
@Component
public class ExpiredBookingScheduler {

        private static final Logger log = LoggerFactory.getLogger(ExpiredBookingScheduler.class);

        @Autowired
        private BookingRepository bookingRepository;
        @Autowired
        private SeatLockService seatLockService;
        @Autowired
        private BookingEventPublisher bookingEventPublisher;

        /**
         * Runs every 2 minutes.
         * Finds PENDING bookings past their expiry time and cleans them up.
         *
         * fixedDelay = 2 min: waits 2 min after the PREVIOUS run completes
         * (not fixed rate) — prevents overlap if a run takes longer than 2 min.
         * initialDelay = 1 min: wait 1 min after startup before first run
         * (gives the service time to fully initialise).
         */
        @Scheduled(fixedDelay = 2 * 60 * 1000, initialDelay = 60 * 1000)
        @Transactional
        public void cleanUpExpiredBookings() {
                LocalDateTime now = LocalDateTime.now();

                // Find all PENDING bookings whose expiry time has passed
                List<Booking> expiredBookings = bookingRepository
                                .findByStatusAndExpiresAtBefore(BookingStatus.PENDING, now);

                if (expiredBookings.isEmpty()) {
                        log.debug("ExpiredBookingScheduler: no expired PENDING bookings found");
                        return;
                }

                log.info("ExpiredBookingScheduler: found {} expired PENDING bookings to process",
                                expiredBookings.size());

                int processed = 0;
                int skipped = 0;

                for (Booking booking : expiredBookings) {
                        try {
                                boolean wasProcessed = processExpiredBooking(booking);
                                if (wasProcessed)
                                        processed++;
                                else
                                        skipped++;
                        } catch (Exception e) {
                                // Log and continue — don't let one bad booking block the rest
                                log.error("Failed to process expired booking: bookingId={} error={}",
                                                booking.getId(), e.getMessage(), e);
                        }
                }

                log.info("ExpiredBookingScheduler complete: processed={} skipped={} total={}",
                                processed, skipped, expiredBookings.size());
        }

        /**
         * Processes a single expired booking.
         *
         * @return true if booking was marked FAILED (processed), false if skipped
         */
        private boolean processExpiredBooking(Booking booking) {
                List<UUID> seatIds = booking.getItems().stream()
                                .map(BookingItem::getSeatId)
                                .toList();

                // ── REDIS CHECK: Is the lock actually gone? ───────────────────────
                // This is the authoritative check. DB expiresAt could be slightly ahead
                // of Redis TTL due to clock skew — Redis is the source of truth.
                boolean anyLockStillActive = seatIds.stream()
                                .anyMatch(seatLockService::isLocked);

                if (anyLockStillActive) {
                        // Lock is still active — booking hasn't truly expired yet
                        // This can happen if clock skew causes DB expiresAt to lag behind Redis TTL
                        log.debug("Booking {} has DB-expired but Redis lock still active — skipping",
                                        booking.getId());
                        return false;
                }

                // ── MARK BOOKING FAILED ───────────────────────────────────────────
                booking.setStatus(BookingStatus.FAILED);
                bookingRepository.save(booking);

                // ── PUBLISH booking-failed ────────────────────────────────────────
                // Consumers:
                // Event Service: release seats back to AVAILABLE (belt-and-suspenders —
                // Redis TTL already did this, but Event Service DB may
                // still show LOCKED status)
                bookingEventPublisher.publishBookingFailed(booking,
                                "Booking expired — payment not completed within 10 minutes");

                log.info("Expired booking cleaned up: bookingId={} userId={} eventId={} seatCount={}",
                                booking.getId(), booking.getUserId(),
                                booking.getEventId(), seatIds.size());

                return true;
        }
}