-- ============================================================
-- V1: Create users table
-- StagePass User Service — Flyway Migration
-- ============================================================

CREATE TABLE users (
    id              UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255)   UNIQUE NOT NULL,
    username        VARCHAR(255),
    password_hash   VARCHAR(255),
    "role"          VARCHAR(20)    NOT NULL DEFAULT 'USER'
        CHECK ("role" IN ('USER', 'ADMIN', 'ORGANISER')),
    auth_provider   VARCHAR(20)    NOT NULL DEFAULT 'LOCAL'
        CHECK (auth_provider IN ('LOCAL', 'GOOGLE', 'GITHUB')),
    provider_id     VARCHAR(255),
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP      NOT NULL DEFAULT NOW()
);

-- Index on auth_provider + provider_id (used heavily during OAuth2 callback logins)
CREATE INDEX idx_users_auth_provider_provider_id
    ON users (auth_provider, provider_id);
