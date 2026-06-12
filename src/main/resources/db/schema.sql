SELECT pg_terminate_backend(pg_stat_activity.pid)
FROM pg_stat_activity
WHERE pg_stat_activity.datname = 'notiguide'
  AND pid <> pg_backend_pid();

SELECT 'CREATE DATABASE notiguide OWNER postgres'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'notiguide')\gexec

\c notiguide;
ALTER DATABASE notiguide SET timezone TO 'Asia/Ho_Chi_Minh';
SELECT pg_reload_conf();

CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE admin_role AS ENUM ('ROLE_SUPER_ADMIN', 'ROLE_ADMIN');

-- Organizations: top-level tenant. A SUPER_ADMIN owns exactly one org and
-- governs every store inside it. Independent stores have org_id = NULL.
CREATE TABLE organization (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    created_by  UUID,  -- FK to admin(id) added at end of file (circular dep)
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TYPE analytics_event_type AS ENUM (
    'TICKET_ISSUED',
    'TICKET_CALLED',
    'TICKET_COMPLETED',
    'TICKET_CANCELLED',
    'TICKET_SKIPPED',
    'DEVICE_TRIGGERED'
);
CREATE TYPE device_kind AS ENUM (
    'RECEIVER_433M',
    'RECEIVER_433M_PASSIVE',
    'RECEIVER_2_4G',
    'TRANSMITTER_HUB'
);
CREATE TYPE device_status AS ENUM (
    'PENDING',
    'PENDING_RF_CODE',
    'ACTIVE',
    'SUSPENDED',
    'DECOMMISSIONED',
    'REJECTED'
);
CREATE TYPE device_rf_ack_status AS ENUM (
    'PENDING',
    'APPLIED',
    'UNCHANGED',
    'REJECTED'
);

CREATE OR REPLACE FUNCTION generate_store_public_id() RETURNS TEXT AS $$
DECLARE
    candidate TEXT;
BEGIN
    LOOP
        -- 6 random bytes → 8 base64 chars; strip non-alphanumeric padding chars.
        candidate := regexp_replace(encode(gen_random_bytes(6), 'base64'), '[^A-Za-z0-9]', '', 'g');

        -- base64 of 6 bytes is always 8 chars but may contain +/=; keep looping until clean 8 chars.
        IF length(candidate) = 8 THEN
            IF to_regclass('store_public_id') IS NULL THEN
                IF NOT EXISTS (
                    SELECT 1 FROM store WHERE lower(public_id) = lower(candidate)
                ) THEN
                    RETURN candidate;
                END IF;
            ELSE
                IF NOT EXISTS (
                    SELECT 1 FROM store WHERE lower(public_id) = lower(candidate)
                ) AND NOT EXISTS (
                    SELECT 1 FROM store_public_id WHERE lower(slug) = lower(candidate)
                ) THEN
                    RETURN candidate;
                END IF;
            END IF;
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE store (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    public_id VARCHAR(8) NOT NULL UNIQUE DEFAULT generate_store_public_id(),
    name VARCHAR(255) NOT NULL,
    address TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    allow_jump_call BOOLEAN DEFAULT FALSE,
    allow_no_show BOOLEAN DEFAULT FALSE,
    org_id UUID REFERENCES organization(id) ON DELETE RESTRICT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_store_public_id ON store(public_id);
CREATE INDEX idx_store_org ON store(org_id);

-- ===== Custom store slugs =====
CREATE TYPE slug_status AS ENUM ('ACTIVE', 'GRACE');

CREATE TABLE store_public_id (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id    UUID NOT NULL REFERENCES store(id) ON DELETE CASCADE,
    slug        VARCHAR(128) NOT NULL,
    is_default  BOOLEAN NOT NULL DEFAULT FALSE,
    status      slug_status NOT NULL DEFAULT 'ACTIVE',
    retired_at  TIMESTAMP WITH TIME ZONE,
    expires_at  TIMESTAMP WITH TIME ZONE,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_default_active   CHECK (NOT is_default OR status = 'ACTIVE'),
    CONSTRAINT chk_grace_timestamps CHECK (
        (status = 'GRACE' AND retired_at IS NOT NULL AND expires_at IS NOT NULL)
        OR (status = 'ACTIVE' AND retired_at IS NULL AND expires_at IS NULL)
    )
);

-- Global uniqueness, case-insensitive — matches the resolver's lower(slug) key.
CREATE UNIQUE INDEX uq_store_public_id_slug ON store_public_id (lower(slug));
-- Exactly one default per store.
CREATE UNIQUE INDEX uq_store_public_id_default ON store_public_id (store_id) WHERE is_default;
-- Cap counting and purge scans.
CREATE INDEX idx_store_public_id_store_status ON store_public_id (store_id, status);
CREATE INDEX idx_store_public_id_expiry ON store_public_id (expires_at) WHERE status = 'GRACE';

-- Mirror each store's default public_id into store_public_id on insert.
CREATE OR REPLACE FUNCTION mirror_store_default_public_id() RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO store_public_id (store_id, slug, is_default, status)
    VALUES (NEW.id, NEW.public_id, TRUE, 'ACTIVE');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_mirror_store_default_public_id
    AFTER INSERT ON store
    FOR EACH ROW EXECUTE FUNCTION mirror_store_default_public_id();

CREATE TABLE admin (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role admin_role NOT NULL,
    store_id UUID REFERENCES store(id) ON DELETE RESTRICT,
    org_id UUID REFERENCES organization(id) ON DELETE RESTRICT,
    is_verified BOOLEAN DEFAULT FALSE,
    created_by UUID REFERENCES admin(id) ON DELETE SET NULL,
    verified_by UUID REFERENCES admin(id) ON DELETE SET NULL,
    verified_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_admin_tenancy CHECK (
        (role = 'ROLE_SUPER_ADMIN' AND org_id IS NOT NULL AND store_id IS NULL) OR
        (role = 'ROLE_ADMIN' AND org_id IS NULL)
    )
);

CREATE UNIQUE INDEX idx_admin_username_lower ON admin(LOWER(username));
CREATE INDEX idx_admin_store ON admin(store_id);
CREATE INDEX idx_admin_org ON admin(org_id);

CREATE TABLE device (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    public_id VARCHAR(32),
    public_key_der BYTEA,
    kind device_kind NOT NULL,
    status device_status NOT NULL DEFAULT 'PENDING',
    assigned_name VARCHAR(100),
    store_id UUID REFERENCES store(id) ON DELETE SET NULL,
    firmware_version VARCHAR(32),
    last_seen_at TIMESTAMP WITH TIME ZONE,
    activated_at TIMESTAMP WITH TIME ZONE,
    hub_slot SMALLINT,
    last_roster_seq INT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_device_public_id ON device(public_id) WHERE public_id IS NOT NULL;
CREATE UNIQUE INDEX idx_device_public_key_der ON device(public_key_der) WHERE public_key_der IS NOT NULL;
CREATE INDEX idx_device_store ON device(store_id);
CREATE INDEX idx_device_status ON device(status);
CREATE INDEX idx_device_kind ON device(kind);

CREATE TABLE device_rf_code (
    device_id UUID PRIMARY KEY REFERENCES device(id) ON DELETE CASCADE,
    payload BYTEA NOT NULL,
    bits SMALLINT NOT NULL,
    byte_len SMALLINT NOT NULL,
    version INT NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    ack device_rf_ack_status NOT NULL DEFAULT 'PENDING',
    ack_at TIMESTAMPTZ,
    CONSTRAINT chk_rf_bits CHECK (bits BETWEEN 1 AND 256),
    CONSTRAINT chk_rf_byte_len CHECK (byte_len BETWEEN 1 AND 32),
    CONSTRAINT chk_rf_byte_math CHECK (byte_len = (bits + 7) / 8)
);

CREATE OR REPLACE FUNCTION device_rf_code_kind_check() RETURNS TRIGGER AS $$
DECLARE
    k device_kind;
BEGIN
    SELECT kind INTO k FROM device WHERE id = NEW.device_id;
    IF k = 'TRANSMITTER_HUB' THEN
        RAISE EXCEPTION 'TRANSMITTER_HUB never owns a device_rf_code row';
    ELSIF k = 'RECEIVER_2_4G' AND (NEW.bits <> 40 OR NEW.byte_len <> 5) THEN
        RAISE EXCEPTION 'RECEIVER_2_4G requires bits = 40 and byte_len = 5';
    ELSIF k = 'RECEIVER_433M' AND NEW.bits NOT BETWEEN 1 AND 32 THEN
        RAISE EXCEPTION 'RECEIVER_433M requires bits BETWEEN 1 AND 32';
    ELSIF k = 'RECEIVER_433M_PASSIVE' AND NEW.bits <> 16 THEN
        RAISE EXCEPTION 'RECEIVER_433M_PASSIVE requires bits = 16';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_device_rf_code_kind_check
    BEFORE INSERT OR UPDATE ON device_rf_code
    FOR EACH ROW EXECUTE FUNCTION device_rf_code_kind_check();

CREATE TABLE analytics_event (
    time TIMESTAMP WITH TIME ZONE NOT NULL,
    store_id UUID REFERENCES store(id) ON DELETE SET NULL,
    event_type analytics_event_type NOT NULL,
    ticket_id UUID,
    wait_duration_seconds INT,
    device_id UUID REFERENCES device(id) ON DELETE SET NULL,
    metadata JSONB
);

SELECT create_hypertable('analytics_event', 'time');

CREATE INDEX idx_analytics_store_time ON analytics_event(store_id, time DESC);
CREATE INDEX idx_analytics_event_type ON analytics_event(event_type, time DESC);

-- Hourly rollup: ticket counts and average wait times per store per hour
-- Note: PERCENTILE_CONT (ordered-set aggregate) is NOT supported in TimescaleDB
-- continuous aggregates. Median is computed at query time from raw data instead.
CREATE MATERIALIZED VIEW analytics_hourly
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 hour', time) AS bucket,
    store_id,
    event_type,
    COUNT(*) AS event_count,
    AVG(wait_duration_seconds) FILTER (WHERE wait_duration_seconds IS NOT NULL) AS avg_wait_seconds
FROM analytics_event
GROUP BY bucket, store_id, event_type
WITH NO DATA;

-- Refresh policy: refresh the last 3 hours every 30 minutes
SELECT add_continuous_aggregate_policy('analytics_hourly',
    start_offset => INTERVAL '3 hours',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '30 minutes'
);

-- Daily rollup: same metrics at day granularity
CREATE MATERIALIZED VIEW analytics_daily
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 day', time) AS bucket,
    store_id,
    event_type,
    COUNT(*) AS event_count,
    AVG(wait_duration_seconds) FILTER (WHERE wait_duration_seconds IS NOT NULL) AS avg_wait_seconds
FROM analytics_event
GROUP BY bucket, store_id, event_type
WITH NO DATA;

SELECT add_continuous_aggregate_policy('analytics_daily',
    start_offset => INTERVAL '3 days',
    end_offset => INTERVAL '1 day',
    schedule_interval => INTERVAL '1 hour'
);

-- Data retention: drop raw events older than 90 days, keep rollups indefinitely
SELECT add_retention_policy('analytics_event', INTERVAL '90 days');

-- Login history for admin accounts
CREATE TABLE login_history (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id    UUID NOT NULL REFERENCES admin(id) ON DELETE CASCADE,
    ip_address  VARCHAR(45) NOT NULL,
    success     BOOLEAN NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_login_history_admin_created
    ON login_history (admin_id, created_at DESC);

-- Store-level queue settings
CREATE TABLE store_settings (
    store_id          UUID PRIMARY KEY REFERENCES store(id) ON DELETE CASCADE,
    max_queue_size    INT NOT NULL DEFAULT 0,
    grace_period_sec  INT NOT NULL DEFAULT 0,
    no_show_action    VARCHAR(10) NOT NULL DEFAULT 'SKIP',
    max_requeues      INT NOT NULL DEFAULT 1,
    requeue_offset    INT NOT NULL DEFAULT 3,
    alert_threshold   INT NOT NULL DEFAULT 2,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Active session tracking for admins
CREATE TABLE admin_session (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_id      UUID NOT NULL REFERENCES admin(id) ON DELETE CASCADE,
    token_hash    VARCHAR(64) NOT NULL UNIQUE,
    ip_address    VARCHAR(45) NOT NULL,
    user_agent    TEXT,
    last_active   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_admin_session_admin ON admin_session (admin_id);
CREATE INDEX idx_admin_session_token_hash ON admin_session (token_hash);

-- Service type routing for multi-counter stores
CREATE TABLE service_type (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id    UUID NOT NULL REFERENCES store(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    prefix      VARCHAR(5) NOT NULL,
    is_active   BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(store_id, prefix)
);

CREATE INDEX idx_service_type_store ON service_type (store_id);

-- Deferred FK: breaks the circular dependency between organization and admin
-- (organization.created_by → admin.id; admin.org_id → organization.id)
ALTER TABLE organization
    ADD CONSTRAINT fk_organization_created_by
    FOREIGN KEY (created_by) REFERENCES admin(id) ON DELETE SET NULL;
