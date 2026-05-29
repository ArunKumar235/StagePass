package org.stagepass.eventservice.controller;

import org.stagepass.eventservice.dto.VenueRequest;
import org.stagepass.eventservice.dto.PagedResponse;
import org.stagepass.eventservice.dto.VenueResponse;
import org.stagepass.eventservice.service.VenueService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * VENUE CONTROLLER
 * Manages venues — the physical locations where events are hosted.
 * Venues are created by admins and reused across events.
 * Organisers pick an existing venue when creating an event.
 * GET endpoints are public (anyone can browse venues).
 * POST / PUT are admin-only (enforced by SecurityConfig + @PreAuthorize).
 */
@RestController
@RequestMapping("/venues")
public class VenueController {

    @Autowired
    private VenueService venueService;

    // ── CREATE ────────────────────────────────────────────────────────────────

    /**
     * POST /venues
     * Admin only. Creates a new venue.
     * Returns 409 if a venue with the same name already exists in that city.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<VenueResponse> createVenue(
            @Valid @RequestBody VenueRequest request) {

        VenueResponse created = venueService.createVenue(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ── GET ALL ───────────────────────────────────────────────────────────────

    /**
     * GET /venues?page=0&size=20&sortBy=name
     * Public endpoint. Returns paginated venues (cached).
     * Organisers call this to pick a venue when creating an event.
     */
    @GetMapping
    public ResponseEntity<PagedResponse<VenueResponse>> getAllVenues(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name") String sortBy) {

        return ResponseEntity.ok(venueService.getAllVenues(page, size, sortBy));
    }

    // ── GET BY ID ─────────────────────────────────────────────────────────────

    /**
     * GET /venues/{id}
     * Public endpoint. Returns a single venue's details.
     */
    @GetMapping("/{venueId}")
    public ResponseEntity<VenueResponse> getVenueById(
            @PathVariable UUID venueId) {

        return ResponseEntity.ok(venueService.getVenueById(venueId));
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────

    /**
     * PUT /venues/{id}
     * Admin only. Updates venue details.
     * Capacity changes are rejected if events are scheduled at this venue.
     */
    @PutMapping("/{venueId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<VenueResponse> updateVenue(
            @PathVariable UUID venueId,
            @Valid @RequestBody VenueRequest request) {

        return ResponseEntity.ok(venueService.updateVenue(venueId, request));
    }
}