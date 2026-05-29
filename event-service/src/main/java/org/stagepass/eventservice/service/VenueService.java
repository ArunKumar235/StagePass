package org.stagepass.eventservice.service;

import org.stagepass.eventservice.dto.VenueRequest;
import org.stagepass.eventservice.dto.PagedResponse;
import org.stagepass.eventservice.dto.VenueResponse;
import org.stagepass.eventservice.entity.Venue;
import org.stagepass.eventservice.exception.EventNotFoundException;
import org.stagepass.eventservice.repository.VenueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@Service
@Transactional
public class VenueService {

    private static final Logger log = LoggerFactory.getLogger(VenueService.class);

    @Autowired
    private VenueRepository venueRepository;

    // ── CREATE ────────────────────────────────────────────────────────────────

    @CacheEvict(value = "venue-list", allEntries = true)
    public VenueResponse createVenue(VenueRequest request) {
        // Prevent duplicate venues in the same city
        if (venueRepository.existsByNameAndCity(request.name(), request.city())) {
            throw new IllegalArgumentException(
                    "A venue named '" + request.name() +
                            "' already exists in " + request.city());
        }

        Venue venue = new Venue();
        venue.setName(request.name());
        venue.setAddress(request.address());
        venue.setCity(request.city());
        venue.setState(request.state());
        venue.setCountry(request.country());
        venue.setTotalCapacity(request.totalCapacity());
        if(request.mapImageUrl() != null) venue.setMapImageUrl(request.mapImageUrl());

        Venue saved = venueRepository.save(venue);
        log.info("Venue created: venueId={} name={} city={}", saved.getId(), saved.getName(), saved.getCity());

        return toResponse(saved);
    }

    // ── GET ALL ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    @Cacheable(value = "venue-list", key = "#page + ':' + #size + ':' + (#sortBy != null ? #sortBy : 'name')")
    public PagedResponse<VenueResponse> getAllVenues(int page, int size, String sortBy) {
        int resolvedPage = Math.max(0, page);
        int resolvedSize = size > 0 ? size : 20;
        String resolvedSort = resolveSort(sortBy);

        Pageable pageable = PageRequest.of(
                resolvedPage,
                resolvedSize,
                Sort.by(Sort.Direction.ASC, resolvedSort)
        );

        Page<VenueResponse> result = venueRepository.findAll(pageable)
                .map(this::toResponse);

        return PagedResponse.of(result);
    }

    // ── GET BY ID ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    @Cacheable(value = "venue-detail", key = "#venueId")
    public VenueResponse getVenueById(UUID venueId) {
        Venue venue = this.getVenueEntityByIdOrThrow(venueId);
        return toResponse(venue);
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────

    @CacheEvict(value = { "venue-list", "venue-detail" }, allEntries = true)
    public VenueResponse updateVenue(UUID venueId, VenueRequest request) {
        Venue venue = this.getVenueEntityByIdOrThrow(venueId);

        if (request.name()         != null) venue.setName(request.name());
        if (request.address()      != null) venue.setAddress(request.address());
        if (request.city()         != null) venue.setCity(request.city());
        if (request.country()      != null) venue.setCountry(request.country());
        if (request.mapImageUrl()  != null) venue.setMapImageUrl(request.mapImageUrl());
        // Note: totalCapacity changes require re-generating seats — not allowed after event creation
        // Only allow capacity update if no events are scheduled at this venue
        if (request.totalCapacity() > 0) {
            long scheduledEvents = venueRepository.countScheduledEvents(venueId);
            if (scheduledEvents > 0) {
                throw new IllegalStateException(
                        "Cannot change venue capacity — " + scheduledEvents +
                                " event(s) are scheduled at this venue.");
            }
            venue.setTotalCapacity(request.totalCapacity());
        }

        Venue saved = venueRepository.save(venue);
        log.info("Venue updated: venueId={}", venueId);

        return toResponse(saved);
    }

    // ── HELPER ───────────────────────────────────────────────────────────────

    public Venue getVenueEntityByIdOrThrow(UUID venueId) {
        return venueRepository.findById(venueId)
                .orElseThrow(() -> new EventNotFoundException("Venue not found: " + venueId));
    }

    private VenueResponse toResponse(Venue venue) {
        return VenueResponse.builder()
                        .id(venue.getId())
                        .name(venue.getName())
                        .address(venue.getAddress())
                        .city(venue.getCity())
                        .state(venue.getState())
                        .country(venue.getCountry())
                        .totalCapacity(venue.getTotalCapacity())
                        .mapImageUrl(venue.getMapImageUrl())
                        .build();
    }

    private String resolveSort(String sortBy) {
        return switch (sortBy != null ? sortBy : "name") {
            case "city" -> "city";
            case "country" -> "country";
            case "capacity" -> "totalCapacity";
            default -> "name";
        };
    }
}
