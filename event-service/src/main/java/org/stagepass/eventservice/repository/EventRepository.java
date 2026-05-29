package org.stagepass.eventservice.repository;

import org.stagepass.eventservice.entity.Event;
import org.stagepass.eventservice.entity.EventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * EVENT REPOSITORY
 *
 * Extends both JpaRepository and JpaSpecificationExecutor.
 *
 * JpaRepository          → standard CRUD: save, findById, findAll, delete, count
 * JpaSpecificationExecutor → dynamic query building via Specification<Event>
 *                           used by EventService.getEvents() for filtered listings
 *
 * Spring Data JPA derives SQL from method names automatically — no @Query needed
 * for simple lookups. @Query is only used for complex joins or bulk updates.
 *
 * Naming conventions used:
 *   findBy    → SELECT WHERE
 *   countBy   → SELECT COUNT WHERE
 *   existsBy  → SELECT EXISTS WHERE
 *   deleteBy  → DELETE WHERE (use with @Modifying)
 *   @Modifying + @Query → UPDATE / bulk DELETE (requires @Transactional on caller)
 */
@Repository
public interface EventRepository
        extends JpaRepository<Event, UUID>, JpaSpecificationExecutor<Event> {

    // ── SINGLE EVENT LOOKUPS ──────────────────────────────────────────────────

    /**
     * Fetches a single event by ID, but only if it has the given status.
     * Used by GET /events/{id} to ensure public callers only see PUBLISHED events.
     *
     * If the event exists but is DRAFT or CANCELLED, this returns Optional.empty()
     * which triggers EventNotFoundException → 404.
     * (Deliberately not distinguishing "doesn't exist" from "not published" — same 404.)
     */
    Optional<Event> findByIdAndStatus(UUID id, EventStatus status);

    /**
     * Finds an event by ID and organiser — used for ownership validation
     * before updates. If the organiser doesn't own the event, returns empty.
     */
    Optional<Event> findByIdAndOrganizerId(UUID id, UUID organizerId);

    // ── ORGANISER QUERIES ─────────────────────────────────────────────────────

    /**
     * Returns all events created by a specific organiser, ordered by event date descending.
     * Used for the organiser's "My Events" dashboard.
     */
    List<Event> findByOrganizerIdOrderByEventDateDesc(UUID organizerId);

    /**
     * Returns events for an organiser filtered by status.
     * Example: findByOrganizerIdAndStatus(id, DRAFT) → organiser's unpublished events.
     */
    List<Event> findByOrganizerIdAndStatus(UUID organizerId, EventStatus status);

    /**
     * Counts how many events a specific organiser has in a given status.
     * Used for dashboard statistics.
     */
    long countByOrganizerIdAndStatus(UUID organizerId, EventStatus status);

    // ── VENUE QUERIES ─────────────────────────────────────────────────────────

    /**
     * Finds all PUBLISHED events at a specific venue, ordered by date.
     * Used when a user clicks on a venue to see upcoming events there.
     */
    List<Event> findByVenueIdAndStatusOrderByEventDateAsc(UUID venueId, EventStatus status);

    /**
     * Counts scheduled (PUBLISHED or DRAFT) events at a venue.
     * Used by VenueService to prevent capacity changes when events are scheduled.
     */
    @Query("""
            SELECT COUNT(e) FROM Event e
            WHERE e.venue.id = :venueId
            AND e.status IN (
                org.stagepass.eventservice.entity.EventStatus.DRAFT,
                org.stagepass.eventservice.entity.EventStatus.PUBLISHED
            )
            """)
    long countScheduledEventsAtVenue(@Param("venueId") UUID venueId);

    // ── STATUS TRANSITION QUERIES ─────────────────────────────────────────────

    /**
     * Bulk status transition — used by admin tools only.
     * Example: mark all past PUBLISHED events as COMPLETED at midnight via a Quartz job.
     *
     * @Modifying + @Transactional on the calling service method is required.
     * clearAutomatically = true ensures Hibernate's first-level cache is cleared
     * after the bulk update so subsequent reads see the new status.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Event e SET e.status = 'COMPLETED'
            WHERE e.status = 'PUBLISHED'
            AND e.eventDate < :cutoffTime
            """)
    int markPastEventsAsCompleted(@Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * Reverts a SOLD_OUT event back to PUBLISHED when a booking is cancelled.
     * Called by EventService.revertFromSoldOut().
     *
     * Targeted UPDATE rather than fetching the entity — avoids an extra SELECT.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Event e SET e.status = 'PUBLISHED'
            WHERE e.id = :eventId
            AND e.status = 'SOLD_OUT'
            """)
    int revertSoldOutToPublished(@Param("eventId") UUID eventId);

    // ── FULL-TEXT SEARCH ──────────────────────────────────────────────────────

    /**
     * Keyword search across title and description using PostgreSQL's full-text search.
     * The GIN index on (to_tsvector(...)) from V1 migration makes this fast.
     *
     * nativeQuery = true because JPQL doesn't support to_tsvector / @@ operators.
     * Only searches PUBLISHED events.
     */
    @Query(value = """
            SELECT * FROM events
            WHERE status = 'PUBLISHED'
            AND to_tsvector('english', title || ' ' || COALESCE(description, ''))
                @@ plainto_tsquery('english', :keyword)
            ORDER BY event_date ASC
            """,
            nativeQuery = true)
    Page<Event> fullTextSearch(@Param("keyword") String keyword, Pageable pageable);

    // ── PAGINATED WITH SPEC ───────────────────────────────────────────────────

    /**
     * Paginated filtered listing — inherited from JpaSpecificationExecutor.
     * Defined here explicitly for clarity (Spring Data provides the implementation).
     *
     * Called by EventService.getEvents() with a dynamically built Specification.
     * Example: status=PUBLISHED AND city=Chennai AND date > tomorrow
     */
    Page<Event> findAll(Specification<Event> spec, Pageable pageable);

    // ── EXISTENCE CHECKS ──────────────────────────────────────────────────────

    /**
     * Checks if an organiser owns a given event.
     * Used as a quick ownership guard before performing expensive operations.
     */
    boolean existsByIdAndOrganizerId(UUID eventId, UUID organizerId);
}