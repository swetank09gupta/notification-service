-- V3: Horizontal-scaling hardening + improved indexes for high-volume workloads
-- Applies to: notifications table (optimistic lock), index coverage improvements

-- ─── Optimistic lock column ───────────────────────────────────────────────────
-- Used by Hibernate @Version to prevent two scheduler instances from concurrently
-- dispatching the same notification. One instance wins; the other retries and finds
-- status=DELIVERED, so no double delivery.
ALTER TABLE notifications
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;


-- ─── Fix RATE_LIMITED status in retry index ───────────────────────────────────
-- The original partial index only covered status='FAILED'.
-- RATE_LIMITED notifications with next_retry_at set must also be indexed
-- for the retry poller query (status IN ('FAILED','RATE_LIMITED')).
DROP INDEX IF EXISTS idx_notif_retry;
CREATE INDEX IF NOT EXISTS idx_notif_retry
    ON notifications(next_retry_at, tenant_id)
    WHERE status IN ('FAILED', 'RATE_LIMITED') AND next_retry_at IS NOT NULL;


-- ─── Covering indexes for common query patterns ───────────────────────────────

-- Delivery report: countByTenantIdAndStatus (GROUP BY tenant + status)
CREATE INDEX IF NOT EXISTS idx_notif_tenant_status_channel
    ON notifications(tenant_id, status, channel);

-- Delivery report: countByTenantIdAndChannelAndStatus
-- The above index already covers this (leading columns: tenant_id, status, channel).

-- Scheduler: scheduled requests due for dispatch
DROP INDEX IF EXISTS idx_nreq_scheduled;
CREATE INDEX IF NOT EXISTS idx_nreq_scheduled
    ON notification_requests(scheduled_at, tenant_id)
    WHERE scheduled_at IS NOT NULL AND status = 'SCHEDULED';

-- Correlation lookups (ORDER_PLACED → ORDER_SHIPPED chain queries)
CREATE INDEX IF NOT EXISTS idx_nreq_tenant_correlation
    ON notification_requests(tenant_id, correlation_id, created_at)
    WHERE correlation_id IS NOT NULL;


-- ─── BRIN indexes for time-series append patterns ─────────────────────────────
-- BRIN (Block Range Index) is ideal for naturally ordered append-only columns.
-- A BRIN index on created_at is ~100x smaller than a B-tree and nearly as fast
-- for range scans on time-ordered data.  Critical at 1PB scale.

CREATE INDEX IF NOT EXISTS idx_notif_created_brin
    ON notifications USING BRIN(created_at) WITH (pages_per_range = 128);

CREATE INDEX IF NOT EXISTS idx_nreq_created_brin
    ON notification_requests USING BRIN(created_at) WITH (pages_per_range = 128);

CREATE INDEX IF NOT EXISTS idx_attempt_created_brin
    ON delivery_attempts USING BRIN(attempted_at) WITH (pages_per_range = 128);


-- ─── Delivery attempt lookup ──────────────────────────────────────────────────
-- Original index was (notification_id, attempt_number).
-- Adding attempted_at for time-range report queries.
DROP INDEX IF EXISTS idx_attempt_notif;
CREATE INDEX IF NOT EXISTS idx_attempt_notif
    ON delivery_attempts(notification_id, attempt_number, attempted_at DESC);
