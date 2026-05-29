package org.stagepass.eventservice.service;

import org.stagepass.eventservice.dto.*;
import org.stagepass.eventservice.entity.*;
import org.stagepass.eventservice.exception.EventNotFoundException;
import org.stagepass.eventservice.exception.UnauthorizedEventAccessException;
import org.stagepass.eventservice.kafka.EventPublisher;
import org.stagepass.eventservice.repository.EventRepository;
import org.stagepass.eventservice.repository.VenueRepository;
import org.stagepass.eventservice.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Service
@Transactional
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    @Autowired private EventRepository   eventRepository;
    @Autowired private VenueRepository   venueRepository;
    @Autowired private SeatService       seatService;
    @Autowired private EventPublisher    eventPublisher;
    @Autowired private UserContext       userContext;

    // ── CREATE EVENT ─────────────────────────────────────────────────────────

    /**
     * Creates a new event in DRAFT status and generates all seat rows.
     *
     * Flow:
     * 1. Validate venue exists
     * 2. Save event as DRAFT
     * 3. Bulk-generate all Seat rows via SeatService
     * 4. Publish event-created Kafka event
     * 5. Evict events-list cache
     *
     * Event starts as DRAFT — not visible to the public until explicitly published.
     * This lets organisers set up events before they go on sale.
     */
    @CacheEvict(value = "events-list", allEntries = true)
    public EventResponse createEvent(CreateEventRequest request) {
        String organizerId = userContext.getUserId();

        Venue venue = null;
        // Validate venue exists
        if(request.venueId() != null) {
             venue = venueRepository.findById(request.venueId())
                    .orElseThrow(() -> new EventNotFoundException(
                            "Venue not found: " + request.venueId()));
        }

        // Build and persist the event
        Event event = new Event();
        event.setTitle(request.title());
        event.setDescription(request.description());
        if(venue != null)                       event.setVenue(venue);
        if(request.eventDate() != null)         event.setEventDate(request.eventDate());
        if(request.doorsOpenTime() != null)     event.setDoorsOpenTime(request.doorsOpenTime());
        if(request.category() != null)          event.setCategory(request.category());
        if(request.bannerImageURL() != null)    event.setBannerImageURL(request.bannerImageURL());

        event.setOrganizerId(UUID.fromString(organizerId));
        event.setStatus(EventStatus.DRAFT); // always starts as DRAFT

        Event savedEvent = eventRepository.save(event);

        // Bulk-generate seat rows (5000 seats = 5000 INSERT rows — uses JDBC batch)
        if(request.tierPricing() != null) {
            seatService.generateSeatsForEvent(savedEvent, request.tierPricing());
        }

        log.info("Event created: eventId={} organizerId={} venueId={}",
                savedEvent.getId(), organizerId, venue==null ? "N/A" : venue.getId());

        return toResponse(savedEvent);
    }

    // ── GET EVENTS (PAGINATED + FILTERED) ────────────────────────────────────

    /**
     * Returns paginated event listings with optional filters.
     *
     * Cache key is based on ALL filter params — different filters = different cache entries.
     * Cache is evicted when any event is created, updated, published, or cancelled.
     *
     * Only PUBLISHED events are returned to public callers.
     *
     * Returns PagedResponse (a serializable wrapper) instead of Spring's Page, which cannot
     * be properly deserialized from Redis by Jackson. PagedResponse contains the same data
     * but in a format that caches and serializes cleanly.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "events-list", key = "#filter.cacheKey()")
    public PagedResponse<EventResponse> getEvents(EventFilterRequest filter) {
        int page = filter.page() != null ? filter.page() : 0;
        int size = filter.size() != null ? filter.size() : 20;

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.ASC, resolveSort(filter.sortBy()))
        );

        // Build dynamic Specification from filter params
        Specification<Event> spec = buildSpecification(filter);

        Page<EventResponse> result = eventRepository.findAll(spec, pageable)
                .map(this::toResponse);

        return PagedResponse.of(result);
    }

    // ── GET SINGLE EVENT ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    @Cacheable(value = "event-detail", key = "#eventId")
    public EventResponse getEventById(UUID eventId) {
        Event event = eventRepository
                .findByIdAndStatus(eventId, EventStatus.PUBLISHED)
                .orElseThrow(() -> new EventNotFoundException(eventId));

        return toResponse(event);
    }

    // ── PUBLISH EVENT ────────────────────────────────────────────────────────

    /**
     * Transitions event from DRAFT → PUBLISHED.
     * Only the organiser who created the event can publish it.
     * Validates that at least one seat exists before publishing.
     */
    @Caching(evict = {
            @CacheEvict(value = "event-detail", key = "#eventId"),
            @CacheEvict(value = "events-list", allEntries = true)
    })
    public EventResponse publishEvent(UUID eventId) {
        Event event = getOwnedEvent(eventId);

        if (event.getStatus() != EventStatus.DRAFT) {
            throw new IllegalStateException(
                    "Only DRAFT events can be published. Current status: " + event.getStatus());
        }

        // Ensure seats were generated — can't publish a seat-less event
        long seatCount = seatService.countSeatsForEvent(eventId);
        if (seatCount == 0) {
            throw new IllegalStateException(
                    "Cannot publish an event with no seats. Generate seats first.");
        }

        event.setStatus(EventStatus.PUBLISHED);
        Event saved = eventRepository.save(event);

        // Publish to Kafka — Notification Service uses this to alert subscribers
        eventPublisher.publishEventCreated(saved);

        log.info("Event published: eventId={} organizerId={}", eventId, userContext.getUserId());

        return toResponse(saved);
    }

    // ── UPDATE EVENT ─────────────────────────────────────────────────────────

    /**
     * Updates mutable event fields.
     * Only the organiser who created the event can update it.
     * Published events can still be updated (description, banner image, etc.)
     * but date changes are only allowed while in DRAFT status.
     */
    @Caching(evict = {
            @CacheEvict(value = "event-detail", key = "#eventId"),
            @CacheEvict(value = "events-list", allEntries = true)
    })
    public EventResponse updateEvent(UUID eventId, UpdateEventRequest request) {
        Event event = getOwnedEvent(eventId);

        // Date changes only allowed while DRAFT — too risky once tickets are on sale
        if (request.eventDate() != null) {
            if (event.getStatus() != EventStatus.DRAFT) {
                throw new IllegalStateException(
                        "Event date can only be changed while event is in DRAFT status.");
            }
            event.setEventDate(request.eventDate());
        }

        if (request.title() != null)          event.setTitle(request.title());
        if (request.description() != null)    event.setDescription(request.description());
        if (request.category() != null)       event.setCategory(request.category());
        if (request.doorsOpenTime() != null)  event.setDoorsOpenTime(request.doorsOpenTime());
        if (request.bannerImageURL() != null) event.setBannerImageURL(request.bannerImageURL());

        if(request.venueId() != null) {
            event.setVenue(
                    venueRepository.findById(request.venueId())
                    .orElseThrow(() -> new EventNotFoundException(
                            "Venue not found: " + request.venueId()))
            );
        }

        Event savedEvent = eventRepository.save(event);


        // Bulk-generate seat rows (5000 seats = 5000 INSERT rows — uses JDBC batch)
        if(request.tierPricing() != null) {
            seatService.deleteSeatSectionsForEvent(eventId); // delete old sections (and their seats, via CASCADE) before generating new ones
            seatService.generateSeatsForEvent(savedEvent, request.tierPricing());
        }

        log.info("Event updated: eventId={} organizerId={} venueId={}", eventId, userContext.getUserId(), request.venueId());

        return toResponse(savedEvent);
    }

    // ── CANCEL EVENT ─────────────────────────────────────────────────────────

    /**
     * Soft-deletes an event by setting status to CANCELLED.
     * Admin only (enforced in SecurityConfig).
     * Publishes event-cancelled Kafka event so:
     *   - Booking Service auto-cancels all bookings
     *   - Notification Service alerts all ticket holders
     */
    @Caching(evict = {
            @CacheEvict(value = "event-detail", key = "#eventId"),
            @CacheEvict(value = "events-list", allEntries = true)
    })
    public void cancelEvent(UUID eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));

        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new IllegalStateException("Event is already cancelled.");
        }

        event.setStatus(EventStatus.CANCELLED);
        eventRepository.save(event);

        // Programmatically evict all seat maps (including all sections)
        seatService.evictSeatMapCacheForEvent(eventId);

        // Downstream services react to this event
        eventPublisher.publishEventCancelled(event);

        log.info("Event cancelled: eventId={} by adminId={}", eventId, userContext.getUserId());
    }

    // ── MARK SOLD OUT ────────────────────────────────────────────────────────

    /**
     * Called internally when BookingEventConsumer detects zero available seats.
     * Not a REST endpoint — triggered by Kafka consumer logic.
     */
    @CacheEvict(value = "event-detail", key = "#eventId")
    public void markAsSoldOut(UUID eventId) {
        eventRepository.findById(eventId).ifPresent(event -> {
            if (event.getStatus() == EventStatus.PUBLISHED) {
                event.setStatus(EventStatus.SOLD_OUT);
                eventRepository.save(event);
                eventPublisher.publishEventSoldOut(eventId);
                log.info("Event marked SOLD_OUT: eventId={}", eventId);
            }
        });
    }

    // ── REVERT FROM SOLD OUT ─────────────────────────────────────────────────

    /**
     * Reverts a SOLD_OUT event back to PUBLISHED when a booking is cancelled.
     *
     * Called by BookingEventConsumer.onBookingCancelled() after a confirmed
     * booking is cancelled and the seat is released back to AVAILABLE.
     *
     * Flow:
     * 1. User cancels a confirmed booking
     * 2. Booking Service publishes booking-cancelled to Kafka
     * 3. BookingEventConsumer sets seat status back to AVAILABLE
     * 4. BookingEventConsumer counts available seats — if > 0, calls this method
     * 5. This method transitions SOLD_OUT → PUBLISHED so the event re-appears
     *    on the listing page and users can book the freed-up seat
     *
     * Idempotency: revertSoldOutToPublished() only updates when status IS SOLD_OUT.
     * If Kafka replays the message, subsequent calls update 0 rows — no side effect.
     *
     * Cache: evicts event-detail and events-list so the listing page shows the
     * event as available again immediately. Seat-map is already evicted by
     * SeatService.updateSeatStatus() which ran before this method is called.
     *
     * Not a REST endpoint — only called internally from BookingEventConsumer.
     */
    @Caching(evict = {
            @CacheEvict(value = "event-detail", key = "#eventId"),
            @CacheEvict(value = "events-list",  allEntries = true)
    })
    public void revertFromSoldOut(UUID eventId) {
        // Targeted UPDATE — only transitions SOLD_OUT → PUBLISHED.
        // Any other current status (PUBLISHED, CANCELLED, COMPLETED) is untouched.
        int rowsUpdated = eventRepository.revertSoldOutToPublished(eventId);

        if (rowsUpdated > 0) {
            log.info("Event reverted SOLD_OUT → PUBLISHED: eventId={} " +
                    "(booking cancellation freed a seat)", eventId);
        } else {
            // Not SOLD_OUT — either already PUBLISHED (another cancellation beat us here)
            // or in a terminal state (CANCELLED / COMPLETED). Both are fine.
            log.debug("revertFromSoldOut: no update applied for eventId={} " +
                    "(event was not SOLD_OUT — already handled or terminal state)", eventId);
        }
    }

    // ── PRIVATE HELPERS ──────────────────────────────────────────────────────

    /**
     * Fetches an event and verifies the current user is its organiser.
     * Throws 403 if ownership check fails.
     */
    private Event getOwnedEvent(UUID eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));

        String currentUserId = userContext.getUserId();
        if (!event.getOrganizerId().toString().equals(currentUserId)) {
            throw new UnauthorizedEventAccessException(
                    "You do not own this event. eventId=" + eventId);
        }
        return event;
    }

    /**
     * Builds a JPA Specification dynamically from the filter request.
     * Only non-null filter values are added to the query.
     */
    private Specification<Event> buildSpecification(EventFilterRequest filter) {
         Specification<Event> response = Specification.<Event>where(hasStatus(EventStatus.PUBLISHED));
                if(filter.city() != null){
                    response = response.and(hasCity(filter.city()));
                }
                if(filter.category() != null){
                    response = response.and(hasCategory(filter.category()));
                }
                if(filter.fromDate() != null){
                    response = response.and(afterDate(filter.fromDate()));
                }
                if(filter.toDate()   != null){
                    response = response.and(beforeDate(filter.toDate()));
                }
                if(filter.keyword()  != null){
                    response = response.and(hasKeyword(filter.keyword()));
                }if(filter.maxPrice() != null){
                    response = response.and(belowPrice(filter.maxPrice()));
                }
        return response;
    }

    private Specification<Event> hasStatus(EventStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status.name());
    }

    private Specification<Event> hasCity(String city) {
        return (root, query, cb) ->
                cb.equal(cb.lower(root.get("venue").get("city")), city.toLowerCase());
    }

    private Specification<Event> hasCategory(String category) {
        return (root, query, cb) ->
                cb.equal(cb.lower(root.get("category")), category.toLowerCase());
    }

    private Specification<Event> afterDate(LocalDate date) {
        return (root, query, cb) ->
                cb.greaterThanOrEqualTo(root.get("eventDate"),
                        date.atStartOfDay());
    }

    private Specification<Event> beforeDate(LocalDate date) {
        return (root, query, cb) ->
                cb.lessThanOrEqualTo(root.get("eventDate"),
                        date.atTime(23, 59, 59));
    }

    private Specification<Event> hasKeyword(String keyword) {
        String pattern = "%" + keyword.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")),       pattern),
                cb.like(cb.lower(root.get("description")), pattern)
        );
    }

    private Specification<Event> belowPrice(BigDecimal maxPrice) {
        // Filter by the lowest seat price in the event's sections
        return (root, query, cb) -> {
            var subquery = query.subquery(BigDecimal.class);
            var seatRoot = subquery.from(Seat.class);
            subquery.select(cb.min(seatRoot.get("price")))
                    .where(cb.equal(
                            seatRoot.get("section").get("event").get("id"),
                            root.get("id")
                    ));
            return cb.lessThanOrEqualTo(subquery, maxPrice);
        };
    }

    private String resolveSort(String sortBy) {
        return switch (sortBy != null ? sortBy : "eventDate") {
            case "price"      -> "sections.seats.price"; // simplified — use native query for complex sorts
            case "popularity" -> "id";                   // placeholder — add booking count later
            default           -> "eventDate";
        };
    }

    private EventResponse toResponse(Event event) {
        // Venue mapping
        VenueResponse venueResponse = null;
        if (event.getVenue() != null) {
             venueResponse = VenueResponse.builder()
                    .id(event.getVenue().getId())
                    .name(event.getVenue().getName())
                    .city(event.getVenue().getCity())
                    .address(event.getVenue().getAddress())
                    .totalCapacity(event.getVenue().getTotalCapacity())
                    .mapImageUrl(event.getVenue().getMapImageUrl())
                    .build();
        }

        return EventResponse.builder()
                .id(event.getId())
                .title(event.getTitle())
                .description(event.getDescription())
                .eventDate(event.getEventDate())
                .doorsOpenTime(event.getDoorsOpenTime())
                .status(event.getStatus())
                .category(event.getCategory())
                .bannerImageURL(event.getBannerImageURL())
                .organizerID(event.getOrganizerId())
                .createdAt(event.getCreatedAt())
                .venue(venueResponse)
                .availableSeatCount(seatService.countAvailableSeats(event.getId()))
                .lowestPrice(seatService.findLowestPriceForEvent(event.getId()))
                .seatTierCounts(seatService.getAvailableCountByTier(event.getId()))
                .build();
    }
}