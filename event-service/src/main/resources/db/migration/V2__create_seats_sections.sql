-- ============================================================
-- V2: Create seat_sections and seats tables
-- StagePass Event Service — Flyway Migration
-- ============================================================
-- Depends on: V1 (events table must exist)
-- ============================================================

-- ── SEAT SECTIONS ────────────────────────────────────────────

-- A section groups seats within an event by tier and physical location.
-- Example: "Floor" (GENERAL), "VIP Pit" (VIP), "Balcony Left" (PREMIUM)
-- One event has multiple sections; each section has many seats.

-- noinspection SqlResolve
CREATE TABLE seat_sections (
                               id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                               event_id       UUID         NOT NULL REFERENCES events(id) ON DELETE CASCADE,
                               section_name   VARCHAR(100) NOT NULL,
                               tier           VARCHAR(20)  NOT NULL
                                   CHECK (tier IN ('GENERAL', 'VIP', 'PREMIUM')),
                               row_count      INT          NOT NULL CHECK (row_count > 0),
                               seats_per_row  INT          NOT NULL CHECK (seats_per_row > 0),
                               created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Lookup sections by event (used when building the seat map)
CREATE INDEX idx_sections_event_id
    ON seat_sections (event_id);

-- ── SEATS ────────────────────────────────────────────────────

-- One row per physical seat in a venue.
-- Generated in bulk by SeatService.generateSeatsForEvent() when an event is created.
-- A 10,000-seat venue = 10,000 rows in this table per event.

CREATE TABLE seats (
                       id           UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
                       section_id   UUID           NOT NULL REFERENCES seat_sections(id) ON DELETE CASCADE,
                       row_label    VARCHAR(5)     NOT NULL,   -- A, B, C... Z, AA, AB...
                       seat_number  INT            NOT NULL CHECK (seat_number > 0),
                       status       VARCHAR(20)    NOT NULL DEFAULT 'AVAILABLE'
                           CHECK (status IN ('AVAILABLE', 'LOCKED', 'BOOKED')),
                       tier         VARCHAR(20)    NOT NULL
                           CHECK (tier IN ('GENERAL', 'VIP', 'PREMIUM')),
                       price        DECIMAL(10, 2) NOT NULL CHECK (price >= 0),

    -- OPTIMISTIC LOCKING: Hibernate @Version field.
    -- Incremented on every UPDATE. If two transactions try to update the same
    -- row concurrently, the second one gets an OptimisticLockException.
    -- This is the database-level double-booking guard (Redis TTL lock is the first line).
                       version      INT            NOT NULL DEFAULT 0,

                       created_at   TIMESTAMP      NOT NULL DEFAULT NOW(),
                       updated_at   TIMESTAMP      NOT NULL DEFAULT NOW(),

    -- Composite unique: no two seats in the same section can share row+number
                       CONSTRAINT uq_seat_position UNIQUE (section_id, row_label, seat_number)
);

-- THE MOST IMPORTANT INDEX in the entire service.
-- Used by the hottest query: "get all available seats for this event"
-- Covers event lookup via section join + status filter in one index scan.
CREATE INDEX idx_seats_section_status
    ON seats (section_id, status);

-- Used for seat map rendering: ordered seat retrieval for an event
-- (event_id is derived via section_id JOIN)
CREATE INDEX idx_seats_section_row_num
    ON seats (section_id, row_label, seat_number);

-- PARTIAL INDEX: fast count of available seats
-- Much smaller than a full index — only indexes AVAILABLE rows
-- Used for the "X seats left" badge and sold-out detection
CREATE INDEX idx_seats_available_partial
    ON seats (section_id)
    WHERE status = 'AVAILABLE';

-- Tier-based filtering (for price range queries and tier availability counts)
CREATE INDEX idx_seats_tier
    ON seats (tier, status);

-- noinspection SqlResolve
CREATE EXTENSION IF NOT EXISTS plpgsql;
-- noinspection SqlResolve
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Auto-update updated_at trigger
CREATE TRIGGER seats_updated_at
    BEFORE UPDATE ON seats
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
-- Note: update_updated_at_column() function was created in V1

-- ── COMMENTS ─────────────────────────────────────────────────
-- Add column-level comments for documentation

COMMENT ON COLUMN seats.version IS
    'Hibernate optimistic locking version. Prevents concurrent double-booking at DB level.';

COMMENT ON COLUMN seats.status IS
    'AVAILABLE: open for booking. LOCKED: held by Redis TTL (10 min). BOOKED: payment confirmed.';

COMMENT ON TABLE seat_sections IS
    'Groups seats by physical location and pricing tier within an event. Created at event creation time.';

COMMENT ON TABLE seats IS
    'One row per physical seat. Bulk-generated by SeatService when an event is created. Status transitions: AVAILABLE → LOCKED (Redis) → BOOKED (Kafka).';