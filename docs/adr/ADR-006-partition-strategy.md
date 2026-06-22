# ADR-006 — Kafka Partition Key Strategy

**Status:** Accepted
**Date:** 2026-06

---

## Context

The service uses 5 Kafka topics. The partition key choice determines:
1. **Ordering guarantees**: messages with the same key go to the same partition → in-order.
2. **Parallelism**: messages with different keys can be processed in parallel.
3. **Fairness**: a single hot key can starve others on the same partition.

Two primary use cases drove the decision:
- **Per-order ordering**: ORDER_PLACED notification must be delivered before ORDER_SHIPPED.
- **Per-tenant fairness**: a high-volume tenant should not starve a low-volume tenant.

---

## Decision

| Topic | Partition key | Rationale |
|---|---|---|
| `notification.requests` | `correlationId` | Per-order ordering of inbound events |
| `notification.dispatch` | `tenantId` | Per-tenant fairness; independent channel parallelism per tenant |
| `notification.retry` | `tenantId` | Same fairness guarantee on retry path |
| `notification.dlq` | `tenantId` | Best-effort; ordering within DLQ is not critical |
| `notification.status` | `correlationId` | Consumers need per-order ordering of status events |

`correlationId` falls back to `tenantId` when not set (e.g. ad-hoc sends without an orderId).

All topics are provisioned with **10 partitions**. This supports up to 10 parallel consumer
instances per topic before repartitioning is required.

---

## Ordering Guarantee for Order Events

`notification.requests` is partitioned by `correlationId` (e.g. `order-abc-123`). All events
for the same order land on the same partition. The single-threaded nature of a Kafka partition
consumer means ORDER_PLACED is consumed before ORDER_SHIPPED — they arrived in order and are
processed in order.

**Additional safety**: `dependsOnIdempotencyKey` in `DispatchService` enforces the prerequisite
at dispatch time. Even if consumers process events out of order (e.g., during consumer
rebalancing), ORDER_SHIPPED will reschedule itself until ORDER_PLACED is DELIVERED.

---

## Consequences

**Positive:**
- Per-order ordering guaranteed end-to-end (ingest → status feedback) with zero coordination.
- `notification.dispatch` partitioned by `tenantId` means a single tenant's burst does not
  delay other tenants at the consumer level.
- Status consumers (Order Service, Analytics) see per-order ordered events on `notification.status`.

**Negative:**
- A tenant with a single `correlationId` (e.g. all notifications tagged with `"global"`) will
  serialize all its events through one partition. Document `correlationId` cardinality guidance
  for upstream teams.
- With 10 partitions and 5 consumer threads, the 6th–10th partitions are consumed by threads
  already busy. Scale consumer concurrency (`KAFKA_CONSUMER_CONCURRENCY`) before adding partitions.

**To change partition count**: must create a new topic and migrate. Changing partitions on
an existing topic changes the key→partition mapping and breaks ordering. Never alter partition
count in-place.
