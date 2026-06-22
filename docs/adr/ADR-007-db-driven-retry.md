# ADR-007 — DB-driven Retry Scheduling

**Status:** Accepted
**Date:** 2026-06

---

## Context

After a transient dispatch failure, notifications need to be retried after a backoff delay.
Two retry scheduling approaches were considered:

1. **Kafka delayed message**: publish the retry event with a delay header. Kafka itself does
   not support delayed messages natively; would require a delay queue service or Kafka Streams.

2. **DB-driven poller**: persist `notifications.next_retry_at` in the DB. A scheduler
   polls every N seconds for rows where `next_retry_at <= now()` and publishes them back
   to `notification.retry`.

---

## Decision

Use a **DB-driven scheduler** (`NotificationScheduler`) polling every 10 seconds:

```sql
SELECT * FROM notifications
WHERE status IN ('FAILED', 'RATE_LIMITED')
  AND next_retry_at <= NOW()
ORDER BY next_retry_at ASC
LIMIT 100
```

The scheduler publishes due rows to `notification.retry`. The Kafka consumer processes them
identically to first attempts.

Backoff formula: `delay = initialDelaySeconds × (backoffMultiplier ^ (attemptNumber - 1))`
- Attempt 1 → 30s
- Attempt 2 → 2m
- Attempt 3 → 8m
- Attempt 4 → 32m
- Attempt 5 → ~2h → EXHAUSTED → DLQ

---

## Consequences

**Positive:**
- `next_retry_at` is durable in the DB — retries survive app restarts.
- No dependency on Kafka delayed message infrastructure.
- Backoff timing is inspectable and adjustable without redeployment (via `app.retry.*` config).
- The DB record is the authoritative state — Kafka events are ephemeral triggers.
- Partial index `idx_notifications_retry` on `(next_retry_at) WHERE status IN ('FAILED', 'RATE_LIMITED')`
  keeps the poll query fast even with millions of rows.

**Negative:**
- In multi-instance deployments the same row may be polled by multiple instances simultaneously.
  Mitigation: `@Transactional` in `DispatchService` ensures only the first writer updates the
  status; subsequent workers skip it via the `status == DELIVERED` guard. Add `SELECT ... FOR UPDATE SKIP LOCKED`
  to the poll query if contention becomes observable.
- 10-second poll interval means a notification may wait up to `backoff + 10s`. Acceptable.
- The scheduler must not schedule a notification already in `PROCESSING` (consumed from Kafka
  but not yet completed). The `status == PROCESSING` check in `executeDispatch` handles this.

**Tuning**: `app.retry.initial-delay-seconds` and `backoff-multiplier` are configurable.
In tests, these are set to 1s initial + 2× multiplier for fast iteration.
