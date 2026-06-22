# Production Scale Reference

Capacity numbers, hard limits, SLAs, bottleneck map, and scaling guidance
for the Multi-tenant Notification Service. All figures are derived from the
architectural choices in this codebase and PostgreSQL/Kafka benchmarks at the
hardware sizes noted. Measure your own deployment — treat these as planning baselines.

---

## Hard Limits (enforced in code, cannot be exceeded without config change)

| Limit | Value | Config key | Why |
|---|---|---|---|
| Batch request size | 500 items | `app.batch.max-size` | Memory bound; prevents single request from exhausting Kafka producer buffer |
| Max retry attempts | 5 | `app.retry.max-attempts` | After 5 attempts the notification is EXHAUSTED and sent to DLQ |
| Kafka message size | 1 MB (Kafka default) | Kafka broker `message.max.bytes` | Limits rendered notification body size |
| Notification body | ~900 KB practical | Derived from Kafka limit | Allows for JSON envelope overhead |
| Concurrent dispatches (global) | 200 in-flight | `app.dispatch.global-concurrency-limit` | Semaphore in BoundedDispatchPool — not on critical path (Kafka consumers are primary) |
| Concurrent dispatches per tenant | 20 in-flight | `app.dispatch.per-tenant-concurrency-limit` | Fair sharing across tenants |
| Kafka consumer threads per instance | 5 | `app.kafka.listener.concurrency` | One thread per partition; scale with partition count |
| Kafka dispatch partitions | 10 | `app.kafka.dispatch-partitions` | Max parallelism across cluster = 10 threads total |
| Kafka requests partitions | 10 | `app.kafka.requests-partitions` | Inbound from upstream services |
| DB connection pool per instance | 20 | `spring.datasource.hikari.maximum-pool-size` | Reduce to 5 when PgBouncer is in front |
| Rate limit window | Fixed 1-min / 1-hr | Redis keys with TTL | Burst at window boundary can be 2× limit; see ADR-003 |
| Scheduled request lookahead | 50 per poll | `LIMIT 50` in `findAndLockDueScheduled` | Keeps poll transaction short |
| Retry batch per poll | 100 per poll | `LIMIT 100` in `findAndLockRetryable` | Keeps poll transaction short |

---

## Throughput Baselines

All numbers assume:
- App server: 4 vCPU, 8 GB RAM
- PostgreSQL: 8 vCPU, 32 GB RAM, SSD (10K IOPS)
- Redis: single node, 4 vCPU
- Kafka: 3-broker cluster, SSD

### Per Single App Instance

| Operation | Sustained | Notes |
|---|---|---|
| HTTP ingestion (`POST /send`) | ~500 RPS | Bounded by DB write (idempotency + request persist, ~3ms each) |
| HTTP batch (`POST /batch`, 500 items) | ~2 batches/sec = ~1,000 notifications/sec ingested | Items processed sequentially before Kafka publish |
| Kafka dispatch throughput | ~1,000 notifications/sec | 5 consumer threads × ~200 dispatches/thread/sec (5ms each: DB read + dispatch + DB write) |
| Rate limit checks | ~5,000/sec | Redis round-trip ~0.5ms, pipelined with dispatch |

### Recommended Production Deployment (5 instances)

| Metric | Value | Calculation |
|---|---|---|
| Ingestion RPS | ~2,500 RPS | 5 instances × 500 RPS |
| Sustained dispatch | ~5,000 notifications/sec | 5 × 1,000/sec — saturates all 10 Kafka partitions |
| Burst absorption | Unlimited (Kafka lag) | HTTP returns 202 immediately; consumer works through backlog |
| Flash-sale burst of 1M notifications | ~3.5 minutes drain time | 1,000,000 / 5,000 per second |
| Daily notification capacity | ~430M/day | 5,000/sec × 86,400 seconds |

### Maximum Theoretical (20 instances, 20 partitions each topic)

> To use more than 10 consumer threads, increase partition count first.
> Consumers beyond partition count sit idle. See "Scaling Guide" below.

| Metric | Value |
|---|---|
| Ingestion RPS | ~10,000 RPS |
| Dispatch | ~20,000 notifications/sec |
| Daily capacity | ~1.7B notifications/day |

---

## Latency SLAs

**"HTTP ingestion" SLA = time for the HTTP response to return, NOT the time for the
notification to reach the recipient.** Both `/send` and `/batch` return before any
channel dispatch occurs — delivery is async via Kafka consumers.

| Path | P50 | P99 | Condition |
|---|---|---|---|
| `POST /send` HTTP response | < 10ms | < 100ms | Returns `202 Accepted` after DB write + Kafka produce |
| `POST /batch` HTTP response (500 items) | < 200ms | < 500ms | Returns `207 Multi-Status` after processing all items |
| Notification delivered (immediate) | < 500ms | < 5s | Kafka consumer picks up within one poll cycle |
| Notification delivered (under 10× burst) | < 30s | < 5min | Consumer works through queue backlog |
| Scheduled notification | `scheduledAt` + 0–10s | `scheduledAt` + 20s | Scheduler polls every 10s |
| Retry — attempt 2 | 30s after failure | 30s + consumer lag | Configurable: `app.retry.initial-delay-seconds` |
| Retry — attempt 3 | ~2 min | | 30s × 4× multiplier |
| Retry — attempt 4 | ~8 min | | |
| Retry — attempt 5 (final) | ~32 min | | → EXHAUSTED → DLQ |
| Total retry window (max) | ~2.5 hours | | Sum of all backoff delays |

---

## Storage Growth

| Table | Row size (avg) | At 1M/day | At 100M/day | At 1B/day |
|---|---|---|---|---|
| `notification_requests` | ~300 bytes | 300 MB/day | 30 GB/day | 300 GB/day |
| `notifications` | ~500 bytes | 500 MB/day* | 50 GB/day | 500 GB/day |
| `delivery_attempts` | ~300 bytes | up to 1.5 GB/day** | up to 150 GB/day | up to 1.5 TB/day |

\* Assumes average 1 channel per request. Multi-channel fans out: 3 channels = 3× rows.

\*\* Worst case: all 5 retry attempts used. Typical (mostly first-attempt success): ~300 MB/day.

### Time to reach 1 PB

| Daily volume | Years to 1 PB |
|---|---|
| 1M notifications/day | ~5,500 years |
| 100M notifications/day | ~55 years |
| 1B notifications/day | ~5.5 years |
| 10B notifications/day | ~200 days |

> At 1B+ notifications/day, table partitioning (see `docs/context/partitioning-strategy.md`)
> and archival to cold storage are mandatory before hitting 6 months of data.

---

## Bottleneck Map

Which component saturates first as load increases:

```
Load increasing →

Phase 1 (0 – 500 RPS):
  Bottleneck: none. Single instance handles comfortably.

Phase 2 (500 – 2,500 RPS):
  Bottleneck: DB write throughput (notification_request INSERT + idempotency check)
  Fix: Add 2–5 app instances. Each has its own DB connection pool.
       Deploy PgBouncer if total connections exceed PostgreSQL max_connections.

Phase 3 (2,500 – 10,000 RPS ingestion / 5,000 – 20,000 dispatch/sec):
  Bottleneck: Kafka partition count (10 partitions = 10 max consumer threads across cluster)
  Fix: Increase partition count to 20 or 40. Add app instances to match.
       Note: partition count can only be increased (not decreased); re-key existing messages.

Phase 4 (20,000+ dispatch/sec):
  Bottleneck: PostgreSQL write throughput (delivery_attempt INSERTs + notification UPDATEs)
  Fix: PostgreSQL read replica for reports. Vertical scale DB. 
       Consider async delivery_attempt writes (fire-and-forget to a separate writer pool).

Phase 5 (100,000+ rate limit checks/sec):
  Bottleneck: Redis single node (~100,000 ops/sec)
  Fix: Redis Cluster (3+ primaries). Update RedisRateLimiterRegistry to use cluster client.

Phase 6 (1PB+ storage):
  Bottleneck: Table size (sequential scan / index bloat)
  Fix: Declarative table partitioning (docs/context/partitioning-strategy.md).
       Drop old month-partitions instead of running DELETE + VACUUM.
```

---

## Multi-tenant Fairness Under Load

The service enforces two levels of tenant isolation under overload:

**Rate limiting (Redis):**
- Default: 60 notifications/min, 1,000/hr per tenant per channel
- Platform admin can set per-tenant overrides: `PUT /api/platform/tenants/{id}/rate-limits/{channel}`
- Overloaded tenants are RATE_LIMITED (rescheduled), not dropped

**Kafka partition fairness:**
- `notification.dispatch` partitioned by `tenantId`
- A single tenant's burst fills at most `1/10th` of dispatch capacity (1 partition out of 10)
- Other tenants continue processing on their partitions unaffected

**Starvation scenario:** If one tenant generates all 10 partitions' worth of traffic
(unlikely with hash partitioning), other tenants experience backlog.
Mitigation: increase partition count so no single tenant dominates.

---

## Scaling Guide: When to Do What

| Signal | Action |
|---|---|
| HTTP P99 > 500ms | Add app instances |
| Kafka consumer lag > 10,000 messages | Add app instances (up to partition count) |
| Kafka consumer lag not decreasing after adding instances | Increase partition count on dispatch + retry topics, then add instances |
| `notifications` table > 500M rows | Enable declarative partitioning (maintenance window) |
| PostgreSQL `max_connections` errors | Deploy PgBouncer; reduce Hikari pool to 5 per instance |
| Redis CPU > 80% | Migrate to Redis Cluster (3 primaries) |
| Retry backlog growing (FAILED rows accumulating) | External channel provider is degraded — circuit breaker should open. Check Resilience4j health endpoint: `GET /actuator/health` |
| DLQ growing | Check for systematic provider failures (invalid addresses, auth errors). These are permanent failures — fix the data, re-send with new idempotency keys |

---

## JVM Sizing Recommendations

| Deployment size | Heap | GC | Notes |
|---|---|---|---|
| Development | 512 MB | ZGC (already set in Dockerfile) | Default Dockerfile setting |
| Production — low volume | 1 GB | ZGC | `-Xms512m -Xmx1g -XX:+UseZGC -XX:+ZGenerational` |
| Production — high volume (1,000+ dispatch/sec) | 2–4 GB | ZGC | Increase to prevent GC pauses under Kafka consumer load |
| Max practical per instance | 8 GB | ZGC | Beyond this, add more instances instead |

ZGC (configured in `Dockerfile`) keeps GC pauses under 1ms regardless of heap size —
critical for P99 latency at high throughput. Do not change to G1GC or Parallel GC.

---

## Observability: Key Metrics to Watch

These are the metrics that indicate scaling action is needed.
Expose via Micrometer + Prometheus integration (add `micrometer-registry-prometheus` to pom.xml):

| Metric | Alert threshold | Meaning |
|---|---|---|
| `kafka.consumer.fetch.manager.records.lag` | > 10,000 | Consumer can't keep up with ingestion rate |
| `notifications.status{status=FAILED}` count rate | > 10% of total | Channel provider degradation |
| `notifications.status{status=EXHAUSTED}` count rate | > 1% | Systematic permanent failures |
| `notifications.status{status=RATE_LIMITED}` backlog | Growing | Rate limits too low or traffic spike |
| `hikari.connections.pending` | > 0 sustained | DB connection pool exhausted → add PgBouncer or instances |
| `resilience4j.circuitbreaker.state{state=OPEN}` | Any | Channel provider is down |
| Redis connection errors | Any | Falling back to in-memory rate limiting — limits no longer cluster-wide |
| `notification_requests` table size | > 500M rows | Plan partitioning window |

Circuit breaker states are already exposed at `GET /actuator/health` via
`management.health.circuitbreakers.enabled: true`.
