package org.stagepass.eventservice.repository;

import org.stagepass.eventservice.entity.Seat;
import org.stagepass.eventservice.entity.SeatStatus;
import org.stagepass.eventservice.entity.SeatTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SEAT REPOSITORY
 *
 * The most query-heavy repository in the event service.
 * Every seat map render, availability check, and status update goes through here.
 *
 * Performance notes:
 * - All hot queries are covered by indexes from V2 migration
 * - Bulk inserts use saveAll() + JDBC batch (configured in application.yml)
 * - Status updates use @Modifying @Query to avoid loading entities into memory
 * - Available count queries use the partial index (WHERE status = 'AVAILABLE')
 *
 * Idempotency note:
 * - updateSeatStatus() and bulkUpdateStatus() always check current status
 *   before applying changes — safe to replay Kafka messages multiple times
 */
@Repository
public interface SeatRepository extends JpaRepository<Seat, UUID> {

    // ── SEAT MAP QUERIES ──────────────────────────────────────────────────────

    /**
     * Fetches ALL seats for an event in the correct rendering order.
     *
     * This is the hottest query in the entire service — called on every seat picker load.
     * Traverses: Seat → SeatSection → Event via JPA join.
     * Covered by idx_seats_section_row_num index from V2 migration.
     *
     * Result is ordered row-label first (A before B), then seat number (1 before 2)
     * so the frontend can group them without any client-side sorting.
     *
     * Redis caches this result for 30 seconds — this DB query runs far less often
     * than the cache hit rate suggests.
     */
    List<Seat> findBySection_Event_IdOrderByRowLabelAscSeatNumberAsc(UUID eventId);

    /**
     * Fetches seats for a single section only.
     * Used by SeatController.getSeatsBySection() for large venues where
     * the frontend renders one section at a time.
     */
    List<Seat> findBySection_IdOrderByRowLabelAscSeatNumberAsc(UUID sectionId);

    /**
     * Fetches seats for a specific section of a specific event.
     * Joins both section and event to prevent cross-event data leaks.
     */
    @Query("""
            SELECT s FROM Seat s
            WHERE s.section.event.id = :eventId
            AND   s.section.id       = :sectionId
            ORDER BY s.rowLabel ASC, s.seatNumber ASC
            """)
    List<Seat> findByEventAndSection(@Param("eventId")   UUID eventId,
                                     @Param("sectionId") UUID sectionId);

    // ── AVAILABILITY QUERIES ──────────────────────────────────────────────────

    /**
     * Returns all AVAILABLE seats for an event.
     * Used when validating a seat selection request from the Booking Service.
     *
     * Covered by idx_seats_section_status index (section_id, status).
     */
    @Query("""
            SELECT s FROM Seat s
            WHERE s.section.event.id = :eventId
            AND   s.status           = 'AVAILABLE'
            ORDER BY s.rowLabel ASC, s.seatNumber ASC
            """)
    List<Seat> findAvailableSeats(@Param("eventId") UUID eventId);

    /**
     * Returns available seat count grouped by tier.
     * Used for "Only X left!" badges and tier availability display.
     *
     * Returns List<Object[]> where each row is [SeatTier, Long count].
     * SeatService.getAvailableCountByTier() maps this into a Map<String, Long>.
     *
     * Covered by idx_seats_tier partial index from V2 migration.
     */
    @Query("""
            SELECT s.tier, COUNT(s)
            FROM Seat s
            WHERE s.section.event.id = :eventId
            AND   s.status           = 'AVAILABLE'
            GROUP BY s.tier
            """)
    List<Object[]> countAvailableSeatsByTier(@Param("eventId") UUID eventId);

    /**
     * Total seat count for an event (all statuses).
     * Used by EventService.publishEvent() to ensure seats exist before publishing.
     */
    long countBySection_Event_Id(UUID eventId);

    /**
     * Seat count filtered by status.
     * Used by SeatService.countAvailableSeatsForEvent() for sold-out detection.
     *
     * Example: countBySection_Event_IdAndStatus(eventId, AVAILABLE) == 0
     *          → event is fully booked → mark SOLD_OUT
     */
    long countBySection_Event_IdAndStatus(UUID eventId, SeatStatus status);

    /**
     * Checks if a specific seat is available right now.
     * Used as a pre-check before attempting to lock.
     */
    @Query("""
            SELECT COUNT(s) > 0 FROM Seat s
            WHERE s.id     = :seatId
            AND   s.status = 'AVAILABLE'
            """)
    boolean isSeatAvailable(@Param("seatId") UUID seatId);

    // ── STATUS UPDATE QUERIES ─────────────────────────────────────────────────

    /**
     * Updates a single seat's status.
     *
     * @Modifying + @Transactional (on calling service method) required.
     * clearAutomatically = true clears Hibernate's first-level cache after
     * the bulk update so subsequent reads see the updated status immediately.
     *
     * Used by SeatService.updateSeatStatus(), called from BookingEventConsumer.
     *
     * IDEMPOTENCY GUARD: Only updates if current status matches expectedCurrentStatus.
     * If a Kafka message is replayed (booking-confirmed delivered twice),
     * the second update finds status = BOOKED (not LOCKED) and updates 0 rows — safe.
     *
     * Returns int (rows affected) so the caller knows if the update was applied.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Seat s
            SET    s.status  = :newStatus,
                   s.version = s.version + 1
            WHERE  s.id      = :seatId
            AND    s.status  = :expectedStatus
            """)
    int updateSeatStatusIfExpected(@Param("seatId")         UUID seatId,
                                   @Param("expectedStatus") SeatStatus expectedStatus,
                                   @Param("newStatus")      SeatStatus newStatus);

    /**
     * Unconditional single-seat status update (no expected-status guard).
     * Use sparingly — prefer updateSeatStatusIfExpected() for Kafka-driven updates.
     * Used by admin operations (e.g. manually releasing a stuck LOCKED seat).
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Seat s
            SET s.status = :newStatus
            WHERE s.id   = :seatId
            """)
    void updateSeatStatus(@Param("seatId")    UUID seatId,
                          @Param("newStatus") SeatStatus newStatus);

    /**
     * Bulk status update for all seats in an event.
     * Used when an event is CANCELLED — all LOCKED seats must revert to AVAILABLE.
     *
     * Only affects seats with the specified current status to avoid
     * accidentally reverting already-BOOKED seats.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Seat s
            SET    s.status = :newStatus
            WHERE  s.section.event.id = :eventId
            AND    s.status           = :currentStatus
            """)
    int bulkUpdateStatusForEvent(@Param("eventId")       UUID eventId,
                                 @Param("currentStatus") SeatStatus currentStatus,
                                 @Param("newStatus")     SeatStatus newStatus);

    // ── SEAT LOOKUP ───────────────────────────────────────────────────────────

    /**
     * Finds a seat by ID and validates it belongs to the given event.
     * Prevents cross-event seat manipulation (e.g. using a seat ID from Event A
     * in a booking request for Event B).
     */
    @Query("""
            SELECT s FROM Seat s
            WHERE s.id               = :seatId
            AND   s.section.event.id = :eventId
            """)
    Optional<Seat> findByIdAndEventId(@Param("seatId")  UUID seatId,
                                      @Param("eventId") UUID eventId);

    /**
     * Finds multiple seats by their IDs, validated against a single event.
     * JOIN FETCHes Section and Event in one query to avoid N+1 lazy-load
     * when validateSeats() accesses seat.getSection().getEvent().getEventDate().
     */
    @Query("""
            SELECT s FROM Seat s
            JOIN FETCH s.section sec
            JOIN FETCH sec.event e
            WHERE s.id    IN :seatIds
            AND   e.id     = :eventId
            """)
    List<Seat> findByIdInAndSection_Event_Id(@Param("seatIds") List<UUID> seatIds,
                                             @Param("eventId") UUID eventId);

    /**
     * Finds a seat by its human-readable position (section + row + number).
     * Useful for support tools and debugging.
     */
    @Query("""
            SELECT s FROM Seat s
            WHERE s.section.id  = :sectionId
            AND   s.rowLabel    = :rowLabel
            AND   s.seatNumber  = :seatNumber
            """)
    Optional<Seat> findByPosition(@Param("sectionId")  UUID sectionId,
                                  @Param("rowLabel")   String rowLabel,
                                  @Param("seatNumber") int seatNumber);

    // ── TIER QUERIES ──────────────────────────────────────────────────────────

    /**
     * Returns all seats of a given tier for an event, filtered by status.
     * Used when a user filters the seat map to show only VIP seats, for example.
     */
    @Query("""
            SELECT s FROM Seat s
            WHERE s.section.event.id = :eventId
            AND   s.tier             = :tier
            AND   s.status           = :status
            ORDER BY s.rowLabel ASC, s.seatNumber ASC
            """)
    List<Seat> findByEventIdAndTierAndStatus(@Param("eventId") UUID eventId,
                                             @Param("tier")    SeatTier tier,
                                             @Param("status")  SeatStatus status);

    // ── LOCKED SEAT QUERIES ───────────────────────────────────────────────────

    /**
     * Returns all LOCKED seats for an event.
     * Used by a Quartz job that periodically checks for seats that have been
     * LOCKED in the DB but whose Redis TTL has already expired (edge case:
     * Redis restarted and lost lock state, but DB still shows LOCKED).
     *
     * Such orphaned locks must be released back to AVAILABLE.
     */
    @Query("""
            SELECT s FROM Seat s
            WHERE s.section.event.id = :eventId
            AND   s.status           = 'LOCKED'
            """)
    List<Seat> findLockedSeatsForEvent(@Param("eventId") UUID eventId);

    void deleteBySection_Event_Id(UUID eventId);

    @Query("""
            SELECT MIN(s.price) FROM Seat s
            WHERE s.section.event.id = :eventId
            AND   s.status           = 'AVAILABLE'
            """)
    Optional<Integer> findLowestPriceByEventId(UUID eventId);

}