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
    'TICKET_SKIPPED',
    'DEVICE_TRIGGERED'
);

CREATE TABLE store (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    address TEXT,
    is_active BOOLEAN DEFAULT TRUE,
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
        (role = 'ROLE_ADMIN' AND store_id IS NOT NULL)
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
