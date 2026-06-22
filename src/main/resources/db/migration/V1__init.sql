CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Tenants
CREATE TABLE tenants (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name          VARCHAR(255) NOT NULL UNIQUE,
    api_key       VARCHAR(255) NOT NULL UNIQUE,
    active        BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP NOT NULL DEFAULT now()
);

-- Users
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(50)  NOT NULL,   -- PLATFORM_ADMIN | TENANT_ADMIN
    tenant_id     UUID REFERENCES tenants(id) ON DELETE CASCADE,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

-- Per-tenant, per-channel rate limit config
CREATE TABLE rate_limit_configs (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    channel             VARCHAR(20) NOT NULL,  -- EMAIL | SMS | PUSH | IN_APP
    requests_per_minute INT NOT NULL DEFAULT 60,
    requests_per_hour   INT NOT NULL DEFAULT 1000,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, channel)
);

-- Templates (versioned)
CREATE TABLE templates (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name        VARCHAR(255) NOT NULL,
    channel     VARCHAR(20)  NOT NULL,
    subject     VARCHAR(500),
    body        TEXT NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT true,
    version     INT NOT NULL DEFAULT 1,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name, channel)
);

-- Channel provider config per tenant
CREATE TABLE channel_configs (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    channel     VARCHAR(20) NOT NULL,
    config_json TEXT,           -- provider-specific settings (mock for now)
    active      BOOLEAN NOT NULL DEFAULT true,
    updated_at  TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, channel)
);

-- Top-level notification request (1 per API call)
CREATE TABLE notification_requests (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id        UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    template_id      UUID REFERENCES templates(id),
    recipient_ref    VARCHAR(255) NOT NULL,  -- logical recipient ID
    variables        TEXT,                   -- JSON map of template variables
    channels         TEXT NOT NULL,          -- comma-separated: EMAIL,SMS,...
    status           VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    scheduled_at     TIMESTAMP,              -- NULL = immediate
    idempotency_key  VARCHAR(255) UNIQUE,
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_nreq_tenant_status    ON notification_requests(tenant_id, status);
CREATE INDEX idx_nreq_scheduled        ON notification_requests(scheduled_at, status)
    WHERE scheduled_at IS NOT NULL AND status = 'PENDING';

-- One row per channel per request
CREATE TABLE notifications (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    request_id       UUID NOT NULL REFERENCES notification_requests(id) ON DELETE CASCADE,
    tenant_id        UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    channel          VARCHAR(20) NOT NULL,
    recipient_address VARCHAR(500) NOT NULL,
    rendered_subject VARCHAR(500),
    rendered_body    TEXT NOT NULL,
    status           VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    attempt_count    INT NOT NULL DEFAULT 0,
    max_attempts     INT NOT NULL DEFAULT 5,
    next_retry_at    TIMESTAMP,
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_notif_retry ON notifications(next_retry_at, status)
    WHERE status = 'FAILED' AND next_retry_at IS NOT NULL;
CREATE INDEX idx_notif_tenant ON notifications(tenant_id, status);

-- Delivery attempt audit trail
CREATE TABLE delivery_attempts (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    notification_id  UUID NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
    attempt_number   INT NOT NULL,
    status           VARCHAR(30) NOT NULL,  -- SUCCESS | FAILED | RATE_LIMITED
    error_message    TEXT,
    channel_response TEXT,
    attempted_at     TIMESTAMP NOT NULL DEFAULT now(),
    duration_ms      BIGINT
);

CREATE INDEX idx_attempt_notif ON delivery_attempts(notification_id, attempt_number);
