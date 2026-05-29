-- ============================================================
-- V2: Add section_name to booking_items table
-- StagePass Booking Service — Flyway Migration
-- ============================================================

ALTER TABLE booking_items ADD COLUMN section_name VARCHAR(255);

COMMENT ON COLUMN booking_items.section_name IS 'Denormalised section name from Event Service — captured at booking time';
