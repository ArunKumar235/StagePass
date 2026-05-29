package org.stagepass.eventservice.repository;

import org.stagepass.eventservice.entity.SeatSection;
import org.stagepass.eventservice.entity.SeatTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SEAT SECTION REPOSITORY
 *
 * SeatSection groups seats by physical area and pricing tier within an event.
 * Examples: "Floor" (GENERAL), "VIP Pit" (VIP), "Balcony Left" (PREMIUM).
 *
 * Sections are created once at event creation (by SeatService.generateSeatsForEvent())
 * and never modified after that — their structure is fixed for the event's lifetime.
 *
 * Queries here are simpler than SeatRepository because sections are a relatively
 * small dataset (typically 3–10 sections per event vs. thousands of seats).
 */
@Repository
public interface SeatSectionRepository extends JpaRepository<SeatSection, UUID> {

    // ── EVENT SECTIONS ────────────────────────────────────────────────────────

    /**
     * Returns all sections for an event.
     * Used by SeatService when building the seat map — sections form the top level
     * of the SeatMapResponse hierarchy (section → rows → seats).
     *
     * Covered by idx_sections_event_id index from V2 migration.
     */
    List<SeatSection> findByEvent_IdOrderBySectionNameAsc(UUID eventId);

    /**
     * Returns sections for an event filtered by tier.
     * Used when a user wants to view only VIP sections of an event.
     */
    List<SeatSection> findByEvent_IdAndTierOrderBySectionNameAsc(UUID eventId, SeatTier tier);

    /**
     * Returns a single section for a specific event.
     * Validates that the section belongs to the event (prevents cross-event access).
     */
    @Query("""
            SELECT ss FROM SeatSection ss
            WHERE ss.id       = :sectionId
            AND   ss.event.id = :eventId
            """)
    Optional<SeatSection> findByIdAndEventId(@Param("sectionId") UUID sectionId,
                                             @Param("eventId")   UUID eventId);

    // ── EXISTENCE AND COUNT ───────────────────────────────────────────────────

    /**
     * Checks if sections already exist for an event.
     * Used by EventService.createEvent() to prevent duplicate seat generation
     * if createEvent() is accidentally called twice for the same event.
     */
    boolean existsByEvent_Id(UUID eventId);

    /**
     * Counts sections per tier for an event.
     * Used for validation: every event should have at least one section per tier
     * declared in the CreateEventRequest.
     */
    long countByEvent_IdAndTier(UUID eventId, SeatTier tier);

    // ── SECTION SUMMARY ───────────────────────────────────────────────────────

    /**
     * Returns section names with their total seat capacity.
     * Used for the event creation summary page: "Floor - 600 seats (GENERAL)".
     *
     * Returns List<Object[]> where each row is:
     *   [0] String sectionName
     *   [1] String tier
     *   [2] Long totalSeats (rowCount × seatsPerRow)
     *
     * Computed as rowCount * seatsPerRow — avoids COUNT(seats) join on a large table.
     */
    @Query("""
            SELECT ss.sectionName,
                   ss.tier,
                   (ss.rowCount * ss.seatsPerRow)
            FROM SeatSection ss
            WHERE ss.event.id = :eventId
            ORDER BY ss.sectionName ASC
            """)
    List<Object[]> getSectionCapacitySummary(@Param("eventId") UUID eventId);

    /**
     * Total capacity of all sections for an event.
     * sum(rowCount × seatsPerRow) — the maximum number of seats the event can sell.
     * Used to validate that the event can actually accommodate the configured seats.
     */
    @Query("""
            SELECT SUM(ss.rowCount * ss.seatsPerRow)
            FROM SeatSection ss
            WHERE ss.event.id = :eventId
            """)
    Long getTotalCapacityForEvent(@Param("eventId") UUID eventId);

    // ── CLEANUP ───────────────────────────────────────────────────────────────

    /**
     * Deletes all sections (and their seats, via CASCADE) for an event.
     * Only used in admin tools for resetting a DRAFT event's seat layout.
     * Never called on PUBLISHED or SOLD_OUT events.
     *
     * ON DELETE CASCADE on the seats table (from V2 migration) means deleting
     * sections automatically removes all child seats — no separate seat deletion needed.
     */
    void deleteByEvent_Id(UUID eventId);
}