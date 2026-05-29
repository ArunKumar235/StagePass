-- ============================================================
-- V1__create_notification_logs.sql
-- Flyway migration — notification_logs table
-- ============================================================

CREATE TABLE IF NOT EXISTS notification_logs
(
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    reference_id        VARCHAR(255) NOT NULL,            -- bookingId | userId | eventId
    notification_type   VARCHAR(50)  NOT NULL,            -- stored as string: BOOKING_CONFIRMED etc.
    recipient_email     VARCHAR(320) NOT NULL,
    status              VARCHAR(20)  NOT NULL,            -- DELIVERED | FAILED
    failure_reason      TEXT,                             -- SMTP / PDF error message; NULL on success
    attempt_count       INT          NOT NULL DEFAULT 1,  -- incremented on each retry
    sent_at             TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_notification_logs PRIMARY KEY (id),
    CONSTRAINT chk_status CHECK (status  IN ('DELIVERED', 'FAILED')),
    CONSTRAINT chk_type   CHECK (notification_type  IN (
                                 'BOOKING_CONFIRMED',
                                 'BOOKING_FAILED',
                                 'BOOKING_CANCELLED',
                                 'EVENT_CANCELLED',
                                 'USER_WELCOME'
                                           ))
    );

-- ── Indexes ───────────────────────────────────────────────────────────────────

-- Idempotency check: existsByReferenceIdAndNotificationTypeAndStatus(...)
-- This query runs on EVERY notification delivery — must be fast.
CREATE INDEX IF NOT EXISTS idx_notif_reference_type_status
    ON notification_logs (reference_id, notification_type, status);

-- Retry tooling: findByStatus(FAILED)
-- Partial index — only indexes FAILED rows, keeping it small.
CREATE INDEX IF NOT EXISTS idx_notif_status_failed
    ON notification_logs (status)
    WHERE status = 'FAILED';

-- Support queries: findByRecipientEmail(email)
-- "What emails has user X received?"
CREATE INDEX IF NOT EXISTS idx_notif_recipient
    ON notification_logs (recipient_email);

-- Optional: order by sent_at when browsing logs
CREATE INDEX IF NOT EXISTS idx_notif_sent_at
    ON notification_logs (sent_at DESC);

-- ── Comments ──────────────────────────────────────────────────────────────────

COMMENT ON TABLE  notification_logs                     IS 'Audit log for every email notification attempt made by the notification-service';
COMMENT ON COLUMN notification_logs.reference_id        IS 'bookingId for booking events, userId for user events, eventId for event events';
COMMENT ON COLUMN notification_logs.notification_type   IS 'NotificationType enum stored as string';
COMMENT ON COLUMN notification_logs.status              IS 'DELIVERED = email sent via SMTP; FAILED = SMTP or PDF error';
COMMENT ON COLUMN notification_logs.failure_reason      IS 'Raw exception message on failure; NULL on success';
COMMENT ON COLUMN notification_logs.attempt_count       IS 'Number of send attempts; incremented on Kafka redelivery';