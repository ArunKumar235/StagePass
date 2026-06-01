package org.stagepass.eventservice.service;

import org.stagepass.eventservice.dto.*;
import org.stagepass.eventservice.entity.*;
import org.stagepass.eventservice.exception.EventNotFoundException;
import org.stagepass.eventservice.repository.SeatRepository;
import org.stagepass.eventservice.repository.SeatSectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class SeatService {

    private static final Logger log = LoggerFactory.getLogger(SeatService.class);

    @Autowired
    private SeatRepository seatRepository;
    @Autowired
    private SeatSectionRepository seatSectionRepository;
    @Autowired
    private CacheManager cacheManager;

    // ── GENERATE SEATS FOR EVENT ─────────────────────────────────────────────

    /**
     * Bulk-generates all Seat rows for a newly created event.
     *
     * Called by EventService.createEvent() immediately after the Event is
     * persisted.
     *
     * How it works:
     * 1. For each SeatTierPricing in the request, create a SeatSection
     * 2. Generate rowCount × seatsPerRow Seat rows with sequential labels (A1,
     * A2... B1, B2...)
     * 3. Bulk-insert all seats using saveAll() with JDBC batching (batch_size=50 in
     * config)
     *
     * Example: GENERAL tier with 20 rows × 30 seats = 600 seats in one batch
     * insert.
     *
     * Row labels: A, B, C... Z, AA, AB... (like a spreadsheet — handles >26 rows)
     * Seat numbers: 1, 2, 3... (left to right within a row)
     */
    public void generateSeatsForEvent(Event event, List<SeatTierPricing> tierPricings) {
        if (tierPricings == null || tierPricings.isEmpty()) {
            throw new IllegalArgumentException("At least one seat tier pricing is required.");
        }

        List<Seat> allSeats = new ArrayList<>();

        for (SeatTierPricing tierPricing : tierPricings) {
            // Create section for this tier
            SeatSection section = new SeatSection();
            section.setEvent(event);
            section.setSectionName(tierPricing.sectionName());
            section.setTier(tierPricing.tier());
            section.setRowCount(tierPricing.rowCount());
            section.setSeatsPerRow(tierPricing.seatsPerRow());

            SeatSection savedSection = seatSectionRepository.save(section);

            // Generate seats for this section
            for (int rowIndex = 0; rowIndex < tierPricing.rowCount(); rowIndex++) {
                String rowLabel = generateRowLabel(rowIndex); // A, B, C... Z, AA...

                for (int seatNum = 1; seatNum <= tierPricing.seatsPerRow(); seatNum++) {
                    Seat seat = new Seat();
                    seat.setSection(savedSection);
                    seat.setRowLabel(rowLabel);
                    seat.setSeatNumber(seatNum);
                    seat.setStatus(SeatStatus.AVAILABLE);
                    seat.setTier(tierPricing.tier());
                    seat.setPrice(tierPricing.price());
                    allSeats.add(seat);
                }
            }
        }

        // Bulk insert — JDBC batch_size=50 in application.yml makes this efficient
        seatRepository.saveAll(allSeats);

        log.info("Generated {} seats for eventId={}", allSeats.size(), event.getId());
    }

    // ── GET SEAT MAP ─────────────────────────────────────────────────────────

    /**
     * Returns the full seat map for an event, grouped by section → row → seat.
     *
     * This is the hot-path endpoint — called every time a user opens the seat
     * picker UI.
     * Cached with a 30-second TTL. Evicted when a booking-confirmed or
     * booking-failed
     * Kafka event updates seat status.
     *
     * Structure:
     * SeatMapResponse
     * └── List<SectionResponse>
     * └── List<RowResponse>
     * └── List<SeatResponse>
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "seat-map", key = "#eventId")
    public SeatMapResponse getSeatMap(UUID eventId) {
        // Fetch all seats for this event in one query, ordered for rendering
        List<Seat> seats = seatRepository
                .findBySection_Event_IdOrderByRowLabelAscSeatNumberAsc(eventId);

        if (seats.isEmpty()) {
            throw new RuntimeException("No seats found for eventId: " + eventId);
        }

        // Group: section → row → seat
        Map<SeatSection, Map<String, List<Seat>>> grouped = seats.stream()
                .collect(Collectors.groupingBy(
                        Seat::getSection,
                        LinkedHashMap::new,
                        Collectors.groupingBy(
                                Seat::getRowLabel,
                                LinkedHashMap::new,
                                Collectors.toList())));

        // Build response DTO
        List<SectionResponse> sectionResponses = grouped.entrySet().stream()
                .map(sectionEntry -> {
                    SeatSection section = sectionEntry.getKey();

                    List<RowResponse> rows = sectionEntry.getValue().entrySet().stream()
                            .map(rowEntry -> {
                                List<SeatResponse> seatResponses = rowEntry.getValue().stream()
                                        .map(seat -> SeatResponse.builder()
                                                .seatId(seat.getId())
                                                .seatNumber(seat.getSeatNumber())
                                                .status(seat.getStatus())
                                                .price(seat.getPrice())
                                                .build())
                                        .collect(Collectors.toList());

                                return new RowResponse(rowEntry.getKey(), seatResponses);
                            })
                            .collect(Collectors.toList());

                    return SectionResponse.builder()
                            .sectionName(section.getSectionName())
                            .seatTier(section.getTier())
                            .rows(rows)
                            .build();
                })
                .collect(Collectors.toList());

        return SeatMapResponse.builder()
                .eventId(eventId)
                .sections(sectionResponses)
                .build();
    }

    // ── GET SEAT MAP BY SECTION ──────────────────────────────────────────────

    /**
     * Returns the seat map for a single section of an event.
     *
     * Used by SeatController.getSeatsBySection() for large venues (10,000+ seats)
     * where loading the full seat map at once is expensive for the frontend to
     * render.
     * The frontend renders one section at a time — user clicks "Floor" → loads
     * Floor only.
     *
     * Differences from getSeatMap():
     * - Scoped to one section → smaller payload, faster response
     * - Cache key includes both eventId and sectionId → independent cache entries
     * per section (evicted independently when that section's seats change)
     * - Validates that sectionId belongs to eventId — prevents cross-event data
     * leaks
     *
     * Cache strategy: same 30-second TTL as the full seat map.
     * Both caches are evicted by the same BookingEventConsumer @CacheEvict call
     * because we use allEntries=false and a specific key — only the affected
     * section's cache is evicted, not all sections.
     *
     * @param eventId   UUID of the event
     * @param sectionId UUID of the section within that event
     * @return SeatMapResponse containing a single SectionResponse
     * @throws EventNotFoundException if sectionId doesn't belong to eventId
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "seat-map", key = "#eventId + ':section:' + #sectionId")
    public SeatMapResponse getSeatMapBySection(UUID eventId, UUID sectionId) {

        // ── STEP 1: Validate section belongs to this event ────────────────
        // Prevents a malicious caller from passing a valid sectionId from a
        // different event and seeing its seat layout.
        SeatSection section = seatSectionRepository
                .findByIdAndEventId(sectionId, eventId)
                .orElseThrow(() -> new EventNotFoundException(
                        "Section", sectionId.toString()));

        // ── STEP 2: Fetch seats for this section only ─────────────────────
        // Uses findBySection_IdOrderByRowLabelAscSeatNumberAsc — covered by
        // idx_seats_section_row_num index. Much smaller result set than getSeatMap().
        List<Seat> seats = seatRepository
                .findBySection_IdOrderByRowLabelAscSeatNumberAsc(sectionId);

        if (seats.isEmpty()) {
            log.warn("No seats found for sectionId={} eventId={}", sectionId, eventId);
            // Return an empty section response rather than throwing — section may
            // legitimately have 0 seats in edge cases (misconfigured event).
            // empty rows
            return SeatMapResponse.builder()
                    .eventId(eventId)
                    .sections(List.of(
                            SectionResponse.builder()
                                    .sectionName(section.getSectionName())
                                    .seatTier(section.getTier())
                                    .rows(List.of()) // empty rows
                                    .build()))
                    .build();
        }

        // ── STEP 3: Group seats by row ────────────────────────────────────
        // Single section → group only by row label (no section grouping needed)
        Map<String, List<Seat>> seatsByRow = seats.stream()
                .collect(Collectors.groupingBy(
                        Seat::getRowLabel,
                        LinkedHashMap::new, // preserve insertion order (A before B)
                        Collectors.toList()));

        // ── STEP 4: Build RowResponse list ────────────────────────────────
        List<RowResponse> rowResponses = seatsByRow.entrySet().stream()
                .map(rowEntry -> {
                    List<SeatResponse> seatResponses = rowEntry.getValue().stream()
                            .map(seat -> SeatResponse.builder()
                                    .seatId(seat.getId())
                                    .seatNumber(seat.getSeatNumber())
                                    .status(seat.getStatus())
                                    .price(seat.getPrice())
                                    .build())
                            .collect(Collectors.toList());

                    return new RowResponse(rowEntry.getKey(), seatResponses);
                })
                .collect(Collectors.toList());

        // ── STEP 5: Wrap in SeatMapResponse with single section ───────────
        SectionResponse sectionResponse = SectionResponse.builder()
                .sectionName(section.getSectionName())
                .seatTier(section.getTier())
                .rows(rowResponses)
                .build();

        SeatMapResponse response = SeatMapResponse.builder()
                .eventId(eventId)
                .sections(List.of(sectionResponse)) // single section only
                .build();
        log.debug("SeatMap by section loaded: eventId={} sectionId={} rows={} seats={}",
                eventId, sectionId, rowResponses.size(), seats.size());

        return response;
    }

    // ── GET AVAILABLE COUNT ──────────────────────────────────────────────────

    /**
     * Returns available seat count grouped by tier.
     * Used for the "only X left" UI badges.
     * Short cache TTL (15s) — changes rapidly during active sales.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "available-count", key = "#eventId")
    public List<SeatTierCount> getAvailableCountByTier(UUID eventId) {
        return seatRepository.countAvailableSeatsByTier(eventId)
                .stream()
                .map(row -> new SeatTierCount((SeatTier) row[0], (Long) row[1]))
                .collect(Collectors.toList());
    }

    /**
     * Programmatically evict seat-map and available-count cache entries for a given
     * event,
     * including all section-specific seat maps.
     */
    public void evictSeatMapCacheForEvent(UUID eventId) {
        Cache seatMapCache = cacheManager.getCache("seat-map");
        if (seatMapCache != null) {
            // Evict full seat map
            seatMapCache.evict(eventId);

            // Evict all section-specific seat maps for this event
            List<SeatSection> sections = seatSectionRepository.findByEvent_IdOrderBySectionNameAsc(eventId);
            for (SeatSection section : sections) {
                seatMapCache.evict(eventId + ":section:" + section.getId());
            }
        }

        Cache countCache = cacheManager.getCache("available-count");
        if (countCache != null) {
            countCache.evict(eventId);
        }
        log.debug("Programmatically evicted all seat-map and count caches for eventId={}", eventId);
    }

    /**
     * Programmatically evict seat-map and available-count cache entries for a
     * specific section
     * and the full seat map of the event.
     */
    public void evictSeatMapCacheForSection(UUID eventId, UUID sectionId) {
        Cache seatMapCache = cacheManager.getCache("seat-map");
        if (seatMapCache != null) {
            // Evict the full seat map
            seatMapCache.evict(eventId);
            // Evict the section-specific seat map
            seatMapCache.evict(eventId + ":section:" + sectionId);
        }

        Cache countCache = cacheManager.getCache("available-count");
        if (countCache != null) {
            countCache.evict(eventId);
        }
        log.debug("Programmatically evicted seat-map and count caches for eventId={}, sectionId={}", eventId,
                sectionId);
    }

    // ── UPDATE SEAT STATUS (called by Kafka consumer) ────────────────────────

    /**
     * Updates a seat's status after a booking event is consumed from Kafka.
     *
     * Called by BookingEventConsumer — never directly by a REST controller.
     *
     * Idempotency guard: checks current status before applying update.
     * If Kafka redelivers a booking-confirmed event, the seat will already
     * be BOOKED — we skip the update rather than throwing an error.
     */
    public void updateSeatStatus(UUID seatId, UUID eventId, SeatStatus newStatus) {
        seatRepository.findById(seatId).ifPresent(seat -> {
            // Idempotency: if status is already what we want, do nothing
            if (seat.getStatus() == newStatus) {
                log.debug("Seat {} is already {}. Skipping update (idempotent).", seatId, newStatus);
                return;
            }

            // Guard against invalid transitions
            // e.g. cannot go from BOOKED back to AVAILABLE via this method
            if (seat.getStatus() == SeatStatus.BOOKED && newStatus == SeatStatus.AVAILABLE) {
                log.warn("Attempted to mark BOOKED seat {} as AVAILABLE. Rejected.", seatId);
                return;
            }

            seat.setStatus(newStatus);
            seatRepository.save(seat);

            // Programmatically evict the precise section cache along with the full map
            // cache
            evictSeatMapCacheForSection(eventId, seat.getSection().getId());

            log.info("Seat status updated: seatId={} newStatus={} eventId={}",
                    seatId, newStatus, eventId);
        });
    }

    /**
     * Updates a seat's status after a booking event is consumed from Kafka.
     *
     * Called by BookingEventConsumer — never directly by a REST controller.
     *
     * Idempotency guard: checks current status before applying update.
     * If Kafka redelivers a booking-cancelled event, the seat will already
     * be AVAILABLE — we skip the update rather than throwing an error.
     */
    public void updateSeatStatusToAvailable(UUID seatId, UUID eventId, SeatStatus newStatus) {
        seatRepository.findById(seatId).ifPresent(seat -> {
            // Idempotency: if status is already what we want, do nothing
            if (seat.getStatus() == newStatus) {
                log.debug("Seat {} is already {}. Skipping update (idempotent).", seatId, newStatus);
                return;
            }

            seat.setStatus(newStatus);
            seatRepository.save(seat);

            // Programmatically evict the precise section cache along with the full map
            // cache
            evictSeatMapCacheForSection(eventId, seat.getSection().getId());

            log.info("Seat status updated: seatId={} newStatus={} eventId={}",
                    seatId, newStatus, eventId);
        });
    }

    // ── COUNT SEATS ──────────────────────────────────────────────────────────

    public long countSeatsForEvent(UUID eventId) {
        return seatRepository.countBySection_Event_Id(eventId);
    }

    public long countAvailableSeatsForEvent(UUID eventId) {
        return seatRepository.countBySection_Event_IdAndStatus(eventId, SeatStatus.AVAILABLE);
    }

    // ── ROW LABEL GENERATOR ──────────────────────────────────────────────────

    /**
     * Converts a 0-based row index to a spreadsheet-style label.
     * 0→A, 1→B ... 25→Z, 26→AA, 27→AB ... 51→AZ, 52→BA ...
     *
     * This handles venues with more than 26 rows cleanly.
     */
    private String generateRowLabel(int rowIndex) {
        StringBuilder label = new StringBuilder();
        int index = rowIndex;
        do {
            label.insert(0, (char) ('A' + (index % 26)));
            index = (index / 26) - 1;
        } while (index >= 0);
        return label.toString();
    }

    public long countAvailableSeats(UUID id) {
        return seatRepository.countBySection_Event_IdAndStatus(id, SeatStatus.AVAILABLE);
    }

    public int findLowestPriceForEvent(UUID id) {
        return seatRepository.findLowestPriceByEventId(id).orElse(0);
    }

    public void deleteSeatSectionsForEvent(UUID eventId) {
        seatSectionRepository.deleteByEvent_Id(eventId);
        log.info("Deleted all seats for eventId={}", eventId);
    }

    // ── SEAT VALIDATION USED BY BOOKING SERVICE
    // ────────────────────────────────────────────────

    /**
     * Validates seat availability for a booking request.
     *
     * FAST PATH — Redis cache:
     * During flash sales, the seat-map cache is warmed by the user browsing the
     * seat picker (GET /events/{eventId}/seats → @Cacheable("seat-map")).
     * Under 100-VU concurrent load, hitting the DB for all 100 simultaneous
     * validateSeats calls means 50 requests wait for HikariCP connections, adding
     * 10-20s of queuing latency. Reading from the Redis cache instead reduces
     * validation to a single HGET taking <<1ms.
     *
     * SLOW PATH — DB fallback:
     * If the cache is cold (first call before seat map has been viewed, or cache
     * was evicted after a booking-confirmed Kafka event), falls back to a direct
     * DB query with JOIN FETCH to avoid N+1 lazy loads.
     *
     * SAFETY:
     * The authoritative double-booking guard is the Redis SETNX seat lock in
     * SeatLockService (Step 2 of the saga). Serving availability from a 30s-TTL
     * cache is safe — stale data at most causes an optimistic validation pass that
     * the seat lock will then correctly reject. BookingService only uses:
     *   - availableAll  → whether to proceed
     *   - totalPrice    → amount to charge
     *   - eventDate     → stored on the booking record (null-safe in caller)
     * seatDetails is not consumed by BookingService.
     */
    public SeatValidationResponse validateSeats(UUID eventId, List<UUID> seatIds) {

        // ── FAST PATH: read from Redis-cached seat map ─────────────────────
        Cache seatMapCache = cacheManager.getCache("seat-map");
        if (seatMapCache != null) {
            SeatMapResponse cached = seatMapCache.get(eventId, SeatMapResponse.class);
            if (cached != null) {
                return validateFromCachedSeatMap(cached, seatIds);
            }
        }

        // ── SLOW PATH: DB query with JOIN FETCH ────────────────────────────
        return validateFromDb(eventId, seatIds);
    }

    /**
     * Validates seats against the Redis-cached SeatMapResponse.
     * Flattens the section→row→seat tree into a Map<seatId, SeatResponse>
     * for O(1) per-seat lookups.
     */
    private SeatValidationResponse validateFromCachedSeatMap(SeatMapResponse cached,
                                                              List<UUID> seatIds) {
        // Flatten cache into seatId → SeatResponse map
        Map<UUID, SeatResponse> seatIndex = cached.sections().stream()
                .flatMap(section -> section.rows().stream())
                .flatMap(row -> row.seats().stream())
                .collect(Collectors.toMap(SeatResponse::seatId, s -> s));

        Set<UUID> unavailableSeatIds = new HashSet<>();
        BigDecimal totalPrice = BigDecimal.ZERO;

        for (UUID seatId : seatIds) {
            SeatResponse seat = seatIndex.get(seatId);
            if (seat == null || seat.status() != SeatStatus.AVAILABLE) {
                unavailableSeatIds.add(seatId);
            } else {
                totalPrice = totalPrice.add(seat.price());
            }
        }

        log.debug("validateSeats (cache hit): eventId={} requested={} unavailable={}",
                cached.eventId(), seatIds.size(), unavailableSeatIds.size());

        return SeatValidationResponse.builder()
                .availableAll(unavailableSeatIds.isEmpty())
                .unavailableSeatIds(new ArrayList<>(unavailableSeatIds))
                .totalPrice(totalPrice)
                .seatDetails(Collections.emptyList()) // not consumed by BookingService
                .eventDate(null)                       // BookingService handles null safely
                .build();
    }

    /**
     * DB fallback: fetches seats with a single JOIN FETCH query (no N+1 lazy loads).
     */
    @Transactional(readOnly = true)
    private SeatValidationResponse validateFromDb(UUID eventId, List<UUID> seatIds) {
        List<Seat> seats = seatRepository.findByIdInAndSection_Event_Id(seatIds, eventId);

        Set<UUID> unavailableSeatIds = seats.stream()
                .filter(seat -> seat.getStatus() != SeatStatus.AVAILABLE)
                .map(Seat::getId)
                .collect(Collectors.toSet());

        BigDecimal totalPrice = seats.stream()
                .filter(seat -> seat.getStatus() == SeatStatus.AVAILABLE)
                .map(Seat::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<SeatValidationResponse.SeatDetail> seatDetails = seats.stream()
                .map(seat -> SeatValidationResponse.SeatDetail.builder()
                        .seatId(seat.getId())
                        .sectionId(seat.getSection().getId())
                        .rowLabel(seat.getRowLabel())
                        .seatNumber(seat.getSeatNumber())
                        .tier(seat.getTier().name())
                        .price(seat.getPrice())
                        .build())
                .collect(Collectors.toList());

        log.debug("validateSeats (DB): eventId={} seats={}", eventId, seats.size());

        return SeatValidationResponse.builder()
                .availableAll(unavailableSeatIds.isEmpty())
                .unavailableSeatIds(new ArrayList<>(unavailableSeatIds))
                .totalPrice(totalPrice)
                .seatDetails(seatDetails)
                .eventDate(seats.isEmpty() ? null : seats.getFirst().getSection().getEvent().getEventDate())
                .build();
    }
}