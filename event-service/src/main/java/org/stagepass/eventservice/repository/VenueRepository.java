package org.stagepass.eventservice.repository;

import org.stagepass.eventservice.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * VENUE REPOSITORY
 *
 * Simple repository — venues don't change often and have straightforward queries.
 * Most queries here are derived from method names (no @Query needed).
 *
 * Venue data is aggressively cached in VenueService (1-hour TTL),
 * so most calls never reach this repository in production.
 */
@Repository
public interface VenueRepository extends JpaRepository<Venue, UUID> {

    // ── DUPLICATE CHECK ───────────────────────────────────────────────────────

    /**
     * Prevents duplicate venues in the same city.
     * Case-sensitive by default — use the @Query variant below for case-insensitive.
     * Called by VenueService.createVenue() before saving.
     */
    boolean existsByNameAndCity(String name, String city);

    /**
     * Case-insensitive duplicate check — more robust for user-entered data.
     * "O2 Arena" and "o2 arena" should be treated as the same venue.
     */
    @Query("""
            SELECT COUNT(v) > 0 FROM Venue v
            WHERE LOWER(v.name) = LOWER(:name)
            AND   LOWER(v.city) = LOWER(:city)
            """)
    boolean existsByNameAndCityIgnoreCase(@Param("name") String name,
                                          @Param("city") String city);

    // ── CITY BROWSING ─────────────────────────────────────────────────────────

    /**
     * Returns all venues in a specific city.
     * Used by the "Browse venues in Chennai" feature.
     * Backed by idx_venues_city index from V1 migration.
     */
    List<Venue> findByCityOrderByNameAsc(String city);

    /**
     * Returns distinct cities that have at least one venue.
     * Used for the city dropdown/filter on the homepage.
     * nativeQuery = true — DISTINCT on a single column is cleaner in SQL.
     */
    @Query(value = "SELECT DISTINCT city FROM venues ORDER BY city ASC",
            nativeQuery = true)
    List<String> findDistinctCities();

    // ── LOOKUP BY NAME ────────────────────────────────────────────────────────

    /**
     * Case-insensitive lookup by name within a city.
     * Used when an organiser searches for a venue by name.
     */
    @Query("""
            SELECT v FROM Venue v
            WHERE LOWER(v.name) LIKE LOWER(CONCAT('%', :name, '%'))
            AND   LOWER(v.city) = LOWER(:city)
            """)
    List<Venue> searchByNameInCity(@Param("name") String name,
                                   @Param("city") String city);

    // ── SCHEDULED EVENT COUNT ─────────────────────────────────────────────────

    /**
     * Counts events that are currently DRAFT or PUBLISHED at a venue.
     * Used by VenueService.updateVenue() to block capacity changes
     * when events are scheduled at the venue.
     *
     * Mirrors EventRepository.countScheduledEventsAtVenue() but accessed
     * from the Venue side of the relationship.
     */
    @Query("""
            SELECT COUNT(e) FROM Event e
            WHERE e.venue.id = :venueId
            AND   e.status IN (
                org.stagepass.eventservice.entity.EventStatus.DRAFT,
                org.stagepass.eventservice.entity.EventStatus.PUBLISHED
            )
            """)
    long countScheduledEvents(@Param("venueId") UUID venueId);

    // ── OPTIONAL LOOKUP ───────────────────────────────────────────────────────

    /**
     * Finds a venue by name in a specific city (exact match, case-insensitive).
     * Returns Optional — callers decide what to do if not found.
     */
    @Query("""
            SELECT v FROM Venue v
            WHERE LOWER(v.name) = LOWER(:name)
            AND   LOWER(v.city) = LOWER(:city)
            """)
    Optional<Venue> findByNameAndCityIgnoreCase(@Param("name") String name,
                                                @Param("city") String city);
}