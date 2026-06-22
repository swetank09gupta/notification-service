# ADR-008 — Horizontal Scaling Design

**Status:** Accepted
**Date:** 2026-06

---

## Context

Multiple app instances must run simultaneously without:
1. Double-delivering the same notification
2. The scheduler on instance A and instance B both retrying the same failed notification
3. Rate limits being enforced per-instance instead of cluster-wide
4. DB connection exhaustion as instance count grows

---

## Decisions

### 1. Scheduler: SELECT FOR UPDATE SKIP LOCKED

`NotificationScheduler.retryPoller()` and `schedulePoller()` run on every instance.
Without locking, 5 instances each pick up the same 100 FAILED rows → 500 retry Kafka events
→ 500 dispatch calls → multiple deliveries.

Fix: native queries use `FOR UPDATE SKIP LOCKED`:
```sql
SELECT * FROM notifications
WHERE status IN ('FAILED', 'RATE_LIMITED') AND next_retry_at <= NOW()
ORDER BY next_retry_at ASC LIMIT 100
FOR UPDATE SKIP LOCKED
```

PostgreSQL semantics: instance A locks rows 1–100. Instance B's query skips those and locks rows 101–200.
Each row is owned by exactly one scheduler at a time. Lock releases when the transaction commits.

Both scheduler methods are wrapped in `@Transactional` — the lock only holds within a transaction.

### 2. Notification dispatch: @Version optimistic lock

`Notification.version` is a `@Version` field. If two dispatch attempts concurrent write the same row:
- First write: `UPDATE notifications SET status=... WHERE id=? AND version=0` → version becomes 1, succeeds
- Second write: same WHERE clause, but version=0 no longer matches → `ObjectOptimisticLockingFailureException`

The exception propagates out of `executeDispatch` to the Kafka consumer.
`DefaultErrorHandler` retries the message (up to 3 times, exponential backoff).
On retry, the notification is read again with `version=1`, status=DELIVERED → early return. No double delivery.

### 3. Rate limiting: Redis cluster-wide (ADR-003)

Redis Lua script enforces rate limits across all instances atomically.
Per-instance TokenBucket fallback only activates when Redis is unreachable.

### 4. Idempotency: DB unique constraint

`notification_requests.idempotency_key UNIQUE` — the DB rejects duplicates regardless of which instance handles the request. `DuplicateRequestException` is returned to the caller.

### 5. Kafka consumer groups

Kafka guarantees each partition is consumed by at most one consumer in a group.
Partitioning by `tenantId` means per-tenant ordering is preserved and no two instances process the same tenant's events simultaneously (for a given partition).

### 6. Circuit breaker: per-instance (known limitation)

Resilience4j circuit breaker state is in-memory, not shared across instances.
Instance A may open its `whatsapp` circuit while instance B still sends to WhatsApp.
- Acceptable: each instance independently learns from failures, circuit opens quickly (5 calls minimum)
- Future improvement: Resilience4j `RedisRegistry` for shared circuit state

---

## Consequences

**Positive:**
- No duplicate deliveries under concurrent scheduler execution
- Cluster-wide rate limiting via Redis
- DB unique constraint prevents duplicate requests at any scale
- Scales horizontally: add instances → Kafka rebalances partitions → linear throughput

**Negative:**
- `FOR UPDATE SKIP LOCKED` adds a row-level lock on the scheduler batch.
  Under very high retry volume, lock contention is possible. Mitigate by reducing `LIMIT` per poll
  or increasing the poll interval.
- Optimistic lock failures increase Kafka consumer retries slightly. Acceptable — they're rare
  (only happen when the same notification is published twice, which the SKIP LOCKED already prevents).
- Circuit breaker state divergence across instances: one instance may open while others are still closed.
  Total outgoing traffic to a degraded provider is reduced but not eliminated until all instances open.

---

## Connection Pool Scaling

Each app instance holds a Hikari pool of 20 DB connections. At 10 instances = 200 connections.
PostgreSQL default `max_connections = 100` → exhausted at 5 instances.

**Required**: deploy PgBouncer in transaction mode between app and PostgreSQL.
See `docs/context/partitioning-strategy.md` for PgBouncer configuration.
With PgBouncer: 1000 client connections → 20 actual PostgreSQL connections.
