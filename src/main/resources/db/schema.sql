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

CREATE TYPE admin_role AS ENUM ('ROLE_SUPER_ADMIN', 'ROLE_ADMIN');
CREATE TYPE analytics_event_type AS ENUM (
    'TICKET_ISSUED',
    'TICKET_CALLED',
    'TICKET_COMPLETED',
    'TICKET_CANCELLED',
    'TICKET_SKIPPED',
    'DEVICE_TRIGGERED'
);

CREATE TABLE store (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    address TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    allow_jump_call BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE admin (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role admin_role NOT NULL,
    store_id UUID REFERENCES store(id) ON DELETE RESTRICT,
    is_verified BOOLEAN DEFAULT FALSE,
    created_by UUID REFERENCES admin(id) ON DELETE SET NULL,
    verified_by UUID REFERENCES admin(id) ON DELETE SET NULL,
    verified_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_superadmin_no_store CHECK (
        (role = 'ROLE_SUPER_ADMIN' AND store_id IS NULL) OR
        (role = 'ROLE_ADMIN')
    )
);

CREATE INDEX idx_admin_store ON admin(store_id);

CREATE TABLE notifier_device (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_token TEXT UNIQUE NOT NULL,
    name VARCHAR(100),
    store_id UUID REFERENCES store(id) ON DELETE CASCADE,
    is_active BOOLEAN DEFAULT TRUE,
    battery_level INT CHECK (battery_level >= 0 AND battery_level <= 100),
    last_ping TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notifier_device_store ON notifier_device(store_id);

CREATE TABLE analytics_event (
    time TIMESTAMP WITH TIME ZONE NOT NULL,
    store_id UUID REFERENCES store(id) ON DELETE SET NULL,
    event_type analytics_event_type NOT NULL,
    ticket_id UUID,
    wait_duration_seconds INT,
    device_id UUID REFERENCES notifier_device(id) ON DELETE SET NULL,
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
