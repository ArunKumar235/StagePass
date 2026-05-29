-- ============================================================
-- V2: Enforce unique Razorpay gateway payment IDs
-- StagePass Payment Service — Flyway Migration
-- ============================================================
-- A Razorpay payment_id must never be reused for a second charge.
-- The payment service now stores the token as soon as a charge starts,
-- so the database must reject a second payment record using the same token.
-- ============================================================

-- Replace the non-unique lookup index from V1 with a unique partial index.
DROP INDEX IF EXISTS idx_payment_records_gateway_id;

CREATE UNIQUE INDEX idx_payment_records_gateway_id
    ON payment_records (gateway_payment_id)
    WHERE gateway_payment_id IS NOT NULL;

COMMENT ON COLUMN payment_records.gateway_payment_id IS
    'Razorpay payment_id (pay_xxx). Stored immediately when a charge starts. '
    'Used by WebhookService to look up our record from incoming webhook events and '
    'to prevent the same token from being charged twice.';
