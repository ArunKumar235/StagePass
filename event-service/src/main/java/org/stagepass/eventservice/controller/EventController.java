package org.stagepass.eventservice.controller;

import org.stagepass.eventservice.dto.*;
import org.stagepass.eventservice.service.EventService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * EVENT CONTROLLER
 *
 * Handles all REST operations on events.
 * Role enforcement is layered:
 *   1. SecurityConfig — URL-level rules (coarse)
 *   2. @PreAuthorize  — method-level rules (fine-grained, e.g. ownership)
 *   3. EventService   — business-level rules (e.g. can't publish a CANCELLED event)
 *
 * This controller never touches the DB or Kafka directly — always delegates to EventService.
 * It also never reads the raw JWT — identity comes from X-User-Id header via UserContext.
 */
@RestController
@RequestMapping("/events")
public class EventController {

    @Autowired private EventService eventService;

    // ── CREATE ────────────────────────────────────────────────────────────────

    /**
     * POST /events
     * Creates a new event in DRAFT status.
     * Only ORGANISER role can access — enforced by SecurityConfig.
     */
    @PostMapping
    @PreAuthorize("hasRole('ORGANISER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<EventResponse> createEvent(
            @Valid @RequestBody CreateEventRequest request) {

        EventResponse created = eventService.createEvent(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ── GET PAGINATED LIST ────────────────────────────────────────────────────

    /**
     * GET /events?city=Chennai&category=CONCERT&fromDate=2025-06-01&page=0&size=20
     * Public endpoint — no auth required.
     * Returns only PUBLISHED events with optional filters.
     */
    @GetMapping
    public ResponseEntity<PagedResponse<EventResponse>> getEvents(
            @ModelAttribute EventFilterRequest filter) {

        return ResponseEntity.ok(eventService.getEvents(filter));
    }

    // ── GET SINGLE EVENT ──────────────────────────────────────────────────────

    /**
     * GET /events/{id}
     * Public endpoint.
     * Returns 404 if event is not found or not PUBLISHED.
     */
    @GetMapping("/{eventId}")
    public ResponseEntity<EventResponse> getEventById(
            @PathVariable UUID eventId) {

        return ResponseEntity.ok(eventService.getEventById(eventId));
    }

    // ── PUBLISH ───────────────────────────────────────────────────────────────

    /**
     * PATCH /events/{id}/publish
     * Transitions event from DRAFT → PUBLISHED.
     * Only the organiser who created the event can publish it.
     * Ownership check happens inside EventService.publishEvent().
     */
    @PatchMapping("/{eventId}/publish")
    @PreAuthorize("hasRole('ORGANISER')")
    public ResponseEntity<EventResponse> publishEvent(
            @PathVariable UUID eventId) {

        return ResponseEntity.ok(eventService.publishEvent(eventId));
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────

    /**
     * PUT /events/{id}
     * Updates mutable event fields.
     * Only the owning organiser can update — enforced inside EventService.
     */
    @PutMapping(value = "/{eventId}")
    @PreAuthorize("hasRole('ORGANISER')")
    public ResponseEntity<EventResponse> updateEvent(
            @PathVariable UUID eventId,
            @Valid @RequestBody UpdateEventRequest request) {

        return ResponseEntity.ok(eventService.updateEvent(eventId, request));
    }

    // ── CANCEL ────────────────────────────────────────────────────────────────

    /**
     * DELETE /events/{id}
     * Soft-deletes the event (sets status = CANCELLED).
     * Admin only — enforced by SecurityConfig.
     * Publishes event-cancelled Kafka event to notify downstream services.
     */
    @DeleteMapping("/{eventId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> cancelEvent(
            @PathVariable UUID eventId) {

        eventService.cancelEvent(eventId);
        return ResponseEntity.noContent().build();
    }
}