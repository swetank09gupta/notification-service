# ADR-002 — Kafka for Async Dispatch Pipeline

**Status:** Accepted
**Date:** 2026-06

---

## Context

Notification dispatch is I/O-bound and can be slow (provider timeouts, retries). Synchronously
dispatching inside the HTTP request handler would:
- Block the caller for seconds per request under load
- Lose in-flight notifications on app restart
- Make flash-sale burst handling impossible (backpressure reaches the HTTP tier)

An in-process async approach (the original `BoundedDispatchPool` with virtual threads +
semaphores) addressed concurrency but still lost in-flight work on restart.

---

## Decision

Use **Apache Kafka** as the async dispatch pipeline:

```
HTTP / Kafka ingest
      │
      ▼
notification.requests    ← upstream publishers (Kafka-first path)
notification.dispatch    ← internal initial dispatch
notification.retry       ← internal retry dispatch
notification.dlq         ← dead-letter queue (exhausted notifications)
notification.status      ← outbound delivery outcomes
```

`NotificationService` persists the request and publishes to `notification.dispatch`.
`NotificationDispatchConsumer` (concurrency=5) calls `DispatchService.executeDispatch`.
The `DefaultErrorHandler` retries infrastructure failures with exponential backoff before
routing to the DLQ.

---

## Consequences

**Positive:**
- HTTP tier always returns 202 immediately after DB persist + Kafka produce.
- In-flight notifications survive app restarts (Kafka consumer group offset is committed
  only after successful processing).
- Horizontal scale: add app instances → Kafka rebalances partitions → linear throughput.
- DLQ provides a safe landing zone for manually re-processing failed notifications.
- Consumer concurrency is configurable via `KAFKA_CONSUMER_CONCURRENCY` env var.

**Negative:**
- Added operational dependency: Kafka + Zookeeper must be running.
- End-to-end latency is slightly higher than in-process dispatch (Kafka round trip).
- Exactly-once semantics require idempotent producers + idempotency key checks
  (already implemented via `notification_requests.idempotency_key` UNIQUE constraint).
- `DefaultErrorHandler` retries are for infrastructure failures only. Business failures
  (channel provider returned an error) are handled entirely within `DispatchService` and
  never surface as exceptions to the Kafka layer.

**Retained artifact:** `BoundedDispatchPool` is still in the codebase. It is not in the
primary dispatch path but is useful for local tooling, batch utilities, and as a fallback
if Kafka is unavailable in a development environment.
