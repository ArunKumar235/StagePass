-- ============================================================
-- V1: Create venues and events tables
-- StagePass Event Service — Flyway Migration
-- ============================================================
-- NEVER edit this file after it has been committed and applied.
-- Any schema changes must be in a new V2__, V3__ etc. file.
-- ============================================================

-- ── VENUES ──────────────────────────────────────────────────

CREATE TABLE venues (
                        id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                        name             VARCHAR(255) NOT NULL,
                        address          VARCHAR(500) NOT NULL,
                        city             VARCHAR(100) NOT NULL,
                        state            VARCHAR(100) NOT NULL,
                        country          VARCHAR(100) NOT NULL,
                        total_capacity   INT          NOT NULL CHECK (total_capacity > 0),
                        map_image_url    VARCHAR(500),
                        created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
                        updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Prevent duplicate venues in the same city
CREATE UNIQUE INDEX idx_venues_name_city
    ON venues (LOWER(name), LOWER(city));

-- Fast lookup by city for venue browsing
CREATE INDEX idx_venues_city
    ON venues (city);

-- ── EVENTS ──────────────────────────────────────────────────

CREATE TABLE events (
                        id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                        title            VARCHAR(255) NOT NULL,
                        description      TEXT,
                        venue_id         UUID         REFERENCES venues(id),
                        event_date       DATE,
                        doors_open_time  TIME,
                        status           VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                            CHECK (status IN ('DRAFT','PUBLISHED','SOLD_OUT','CANCELLED','COMPLETED')),
                        category         VARCHAR(50),
                        banner_image_url VARCHAR(500),
                        organizer_id     UUID         NOT NULL,  -- References user-service (no FK — different DB)
                        created_by       UUID,                   -- JPA @CreatedBy — set by AuditConfig
                        last_modified_by UUID,                   -- JPA @LastModifiedBy
                        created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
                        updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Filter PUBLISHED events fast (most common query)
CREATE INDEX idx_events_status
    ON events (status);

-- Date range filtering for event browsing
CREATE INDEX idx_events_event_date
    ON events (event_date);

-- Organiser's own event management
CREATE INDEX idx_events_organizer_id
    ON events (organizer_id);

-- Combined index for the most common query: published events by date in a city
-- Requires a join to venues, but this helps the planner
CREATE INDEX idx_events_status_date
    ON events (status, event_date)
    WHERE status = 'PUBLISHED';

-- Category filtering
CREATE INDEX idx_events_category
    ON events (category);

-- Full-text search on title and description (keyword search)
-- Uses PostgreSQL's built-in text search
CREATE INDEX idx_events_fts
    ON events USING gin(to_tsvector('english', title || ' ' || COALESCE(description, '')));

-- ── AUTO-UPDATE updated_at ─────────────────────────────────
-- Trigger function that sets updated_at = NOW() on every UPDATE

CREATE OR REPLACE FUNCTION update_updated_at_column()
    RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER venues_updated_at
    BEFORE UPDATE ON venues
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER events_updated_at
    BEFORE UPDATE ON events
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();