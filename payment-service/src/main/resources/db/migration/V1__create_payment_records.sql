-- ============================================================
-- V1: Create payment_records and refund_records tables
-- StagePass Payment Service — Flyway Migration
-- ============================================================
-- Payment Service has its own isolated PostgreSQL database (payment_db).
-- No FK constraints to other services — cross-service references are plain UUIDs.
-- Every charge attempt and refund is persisted here for full audit trail.
-- ============================================================

-- ── PAYMENT RECORDS ───────────────────────────────────────────

-- One row per payment attempt. Includes both successful and failed attempts.
-- This is the complete financial audit trail — never delete rows from this table.

CREATE TABLE payment_records (
                                 id                  UUID           PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Cross-service references — plain UUIDs, no FK constraints
                                 booking_id          UUID           NOT NULL,    -- References Booking Service
                                 user_id             UUID           NOT NULL,    -- References User Service

    -- Razorpay's transaction ID — set after successful gateway call
    -- NULL for PENDING records or if gateway call never completed
                                 gateway_payment_id  VARCHAR(255),

                                 amount              DECIMAL(10, 2) NOT NULL CHECK (amount > 0),
                                 currency            VARCHAR(10)    NOT NULL DEFAULT 'INR',

                                 status              VARCHAR(20)    NOT NULL DEFAULT 'PENDING'
                                     CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED')),

                                 method              VARCHAR(20)    NOT NULL
                                     CHECK (method IN ('CARD', 'UPI', 'WALLET', 'NETBANKING')),

    -- Populated on FAILED status — from Razorpay's error_description
                                 failure_reason      TEXT,

    -- Optimistic locking — prevents concurrent webhook + API call updating same record
    -- Hibernate @Version field
                                 version             INT            NOT NULL DEFAULT 0,

                                 created_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
                                 updated_at          TIMESTAMP      NOT NULL DEFAULT NOW()
);

-- ── IDEMPOTENCY CONSTRAINT ────────────────────────────────────
-- THE MOST IMPORTANT CONSTRAINT in this table.
-- Guarantees exactly one PaymentRecord per booking at the DB level.
-- Even if application-layer idempotency (Redis check) fails,
-- the DB will reject a second INSERT for the same booking_id
-- with a unique constraint violation — caught as DuplicatePaymentException.
CREATE UNIQUE INDEX idx_payment_records_booking_id
    ON payment_records (booking_id);

-- Webhook handler: look up our record by Razorpay's payment ID
CREATE INDEX idx_payment_records_gateway_id
    ON payment_records (gateway_payment_id)
    WHERE gateway_payment_id IS NOT NULL;

-- User payment history (admin queries, support lookups)
CREATE INDEX idx_payment_records_user_id
    ON payment_records (user_id, created_at DESC);

-- Reconciliation: find stale PENDING payments older than threshold
-- Partial index — only PENDING rows, keeps it small and fast
CREATE INDEX idx_payment_records_pending_created
    ON payment_records (created_at)
    WHERE status = 'PENDING';

-- Status + date filtering for reporting
CREATE INDEX idx_payment_records_status_created
    ON payment_records (status, created_at DESC);

-- ── REFUND RECORDS ────────────────────────────────────────────

-- One row per refund attempt. A FAILED refund can be retried,
-- creating a new row (idempotency keyed by paymentId ensures
-- at most one SUCCESSFUL refund per payment).

CREATE TABLE refund_records (
                                id                  UUID           PRIMARY KEY DEFAULT gen_random_uuid(),

    -- FK to payment_records — within same DB, FK is safe
                                payment_id          UUID           NOT NULL REFERENCES payment_records(id),

    -- Razorpay's refund transaction ID — set after successful refund submission
                                gateway_refund_id   VARCHAR(255),

                                amount              DECIMAL(10, 2) NOT NULL CHECK (amount > 0),

                                status              VARCHAR(20)    NOT NULL DEFAULT 'PENDING'
                                    CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED')),

    -- Reason for refund — passed to Razorpay for their records
                                reason              VARCHAR(100)   DEFAULT 'BOOKING_CANCELLED',

                                created_at          TIMESTAMP      NOT NULL DEFAULT NOW(),

    -- Set when Razorpay confirms the refund was processed (via webhook)
                                processed_at        TIMESTAMP
);

-- UNIQUE constraint: at most one SUCCESSFUL refund per payment.
-- Allows multiple FAILED attempts (user can retry after failure),
-- but prevents double-refunding a single payment.
-- Partial index on status = 'SUCCESS' enforces this without blocking retries.
CREATE UNIQUE INDEX idx_refund_records_payment_success
    ON refund_records (payment_id)
    WHERE status = 'SUCCESS';

-- Webhook handler: look up our refund record by Razorpay's refund ID
CREATE INDEX idx_refund_records_gateway_refund_id
    ON refund_records (gateway_refund_id)
    WHERE gateway_refund_id IS NOT NULL;

-- Fetch refunds for a payment (support queries, status checks)
CREATE INDEX idx_refund_records_payment_id
    ON refund_records (payment_id);

-- Reconciliation: find PENDING refunds submitted but not yet confirmed via webhook
CREATE INDEX idx_refund_records_pending
    ON refund_records (created_at)
    WHERE status = 'PENDING';

-- ── AUTO-UPDATE updated_at ────────────────────────────────────

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER payment_records_updated_at
    BEFORE UPDATE ON payment_records
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ── COMMENTS ─────────────────────────────────────────────────

COMMENT ON TABLE payment_records IS
    'Complete audit trail of all payment attempts. Never delete rows. '
    'One row per charge attempt — includes both SUCCESS and FAILED attempts. '
    'UNIQUE constraint on booking_id enforces exactly-once charging at DB level.';

COMMENT ON TABLE refund_records IS
    'Audit trail of refund attempts. Partial UNIQUE index on (payment_id) WHERE status=SUCCESS '
    'prevents double-refunding while allowing FAILED refunds to be retried.';

COMMENT ON COLUMN payment_records.gateway_payment_id IS
    'Razorpay transaction ID (pay_xxx). NULL until gateway call completes. '
    'Used by WebhookService to look up our record from incoming webhook events.';

COMMENT ON COLUMN payment_records.version IS
    'Hibernate @Version field for optimistic locking. '
    'Prevents concurrent webhook update and API update colliding on the same record.';

COMMENT ON COLUMN refund_records.processed_at IS
    'Set by WebhookService when refund.processed webhook arrives from Razorpay. '
    'Razorpay refunds typically settle in 5-7 business days.';