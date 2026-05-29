-- ============================================================
-- V1: Create bookings and booking_items tables
-- StagePass Booking Service — Flyway Migration
-- ============================================================
-- Booking Service has its own isolated PostgreSQL database (booking_db).
-- No foreign keys to Event Service or User Service tables —
-- they live in different databases. References are stored as plain UUIDs.
-- ============================================================

-- ── BOOKINGS ─────────────────────────────────────────────────

CREATE TABLE bookings (
                          id              UUID           PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Cross-service references stored as plain UUIDs (no FK constraints)
                          user_id         UUID           NOT NULL,   -- References users table in user-service DB
                          event_id        UUID           NOT NULL,   -- References events table in event-service DB

                          status          VARCHAR(20)    NOT NULL DEFAULT 'PENDING'
                              CHECK (status IN ('PENDING','CONFIRMED','FAILED','CANCELLED')),

                          total_amount    DECIMAL(10,2)  NOT NULL CHECK (total_amount >= 0),
                          payment_method  VARCHAR(20)    NOT NULL,   -- CARD, UPI, WALLET
                          payment_id      VARCHAR(255),              -- Set by Payment Service on success; null until then

    -- Denormalised from Event Service at booking time.
    -- Stored so CancellationService can check refund eligibility without
    -- calling Event Service (which may be down during cancellation).
                          event_date      TIMESTAMP,
    -- we don't need to store event date, because if we change the event date, then bookings will hold the old date,
    -- which is not correct. We can fetch the event date from event service when we need to check refund eligibility.

    -- DB-side expiry for PENDING bookings.
    -- Matches the Redis TTL (10 min from creation).
    -- Used by ExpiredBookingScheduler to find abandoned checkouts.
                          expires_at      TIMESTAMP      NOT NULL,

                          created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
                          confirmed_at    TIMESTAMP,                 -- Set when status → CONFIRMED
                          cancelled_at    TIMESTAMP                  -- Set when status → CANCELLED
);

-- ── BOOKING_ITEMS ─────────────────────────────────────────────

-- One row per seat per booking.
-- Seat details are denormalised from Event Service at booking creation time.
-- This ensures ticket data is accurate even if Event Service changes seat info later.
-- "What was booked" must be immutable — never join to Event Service for ticket rendering.

CREATE TABLE booking_items (
                               id          UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
                               booking_id  UUID           NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,

    -- Cross-service seat reference — plain UUID, no FK
                               seat_id     UUID           NOT NULL,
                               section_id  UUID           NOT NULL,

    -- Denormalised seat details — captured at booking time, never updated
                               row_label   VARCHAR(5)     NOT NULL,       -- A, B, C... AA, AB...
                               seat_number INT            NOT NULL CHECK (seat_number > 0),
                               tier        VARCHAR(20)    NOT NULL CHECK (tier IN ('GENERAL','VIP','PREMIUM')),
                               price       DECIMAL(10,2)  NOT NULL CHECK (price >= 0),

    -- Prevent the same seat appearing twice in one booking
                               CONSTRAINT uq_booking_seat UNIQUE (booking_id, seat_id)
);

-- ── INDEXES ───────────────────────────────────────────────────

-- User's booking history (most frequent query — GET /bookings/my)
CREATE INDEX idx_bookings_user_id
    ON bookings (user_id, created_at DESC);

-- Admin: all bookings for an event
CREATE INDEX idx_bookings_event_id
    ON bookings (event_id);

-- ExpiredBookingScheduler query: PENDING bookings past their expiry time
-- Partial index — only indexes PENDING rows, keeping it small and fast
CREATE INDEX idx_bookings_pending_expires
    ON bookings (expires_at)
    WHERE status = 'PENDING';

-- EventCancellationConsumer: find all active bookings for an event
CREATE INDEX idx_bookings_event_status
    ON bookings (event_id, status);

-- BookingItemRepository: fetch items for a booking (used in toResponse mapping)
CREATE INDEX idx_booking_items_booking_id
    ON booking_items (booking_id);

-- Double-booking guard at DB level:
-- Check if a seat already has a CONFIRMED booking before confirming a new one
CREATE INDEX idx_booking_items_seat_id
    ON booking_items (seat_id);

-- ── COMMENTS ──────────────────────────────────────────────────

COMMENT ON TABLE bookings IS
    'Core booking entity. Each row represents one booking attempt by a user for one or more seats at an event. Status lifecycle: PENDING → CONFIRMED (payment success) or FAILED (payment failed/timeout). CONFIRMED → CANCELLED (user cancels).';

COMMENT ON TABLE booking_items IS
    'One row per seat in a booking. Seat details are denormalised from Event Service at booking creation — never updated after creation to preserve the exact state of what was purchased.';

COMMENT ON COLUMN bookings.expires_at IS
    'DB-level expiry matching Redis TTL (10 min from creation). Used by ExpiredBookingScheduler to detect abandoned checkouts where Redis lock expired but DB status is still PENDING.';

COMMENT ON COLUMN bookings.event_date IS
    'Denormalised from Event Service at booking time. Used by CancellationService to check refund eligibility without making a cross-service call.';

COMMENT ON COLUMN booking_items.price IS
    'Price at time of booking — immutable. If Event Service changes seat pricing later, this preserves what the user actually paid.';

