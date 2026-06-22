-- V2: Add correlation / event-type / dependency columns to support
-- event-driven ingestion (notification.requests Kafka topic) and
-- ordered multi-notification flows (ORDER_PLACED → ORDER_SHIPPED → ORDER_DELIVERED).

ALTER TABLE notification_requests
    ADD COLUMN IF NOT EXISTS correlation_id              VARCHAR(255),
    ADD COLUMN IF NOT EXISTS event_type                  VARCHAR(100),
    ADD COLUMN IF NOT EXISTS depends_on_idempotency_key  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS source                      VARCHAR(20) DEFAULT 'HTTP';
    -- source: HTTP | KAFKA_EVENT

-- Speed up per-order status queries from the calling service
CREATE INDEX IF NOT EXISTS idx_nreq_correlation
    ON notification_requests(tenant_id, correlation_id)
    WHERE correlation_id IS NOT NULL;

-- Speed up dependency checks: "is prerequisite key fully delivered?"
CREATE INDEX IF NOT EXISTS idx_nreq_idempotency_key
    ON notification_requests(idempotency_key)
    WHERE idempotency_key IS NOT NULL;
