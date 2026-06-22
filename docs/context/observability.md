# Observability Reference

## Two Ingestion Paths (important for latency interpretation)

**The service has two ingestion paths.** Both return immediately — neither path blocks on
actual notification delivery:

| Path | Endpoint | Returns | Latency SLA |
|---|---|---|---|
| **HTTP ingestion** | `POST /api/tenants/{id}/notifications/send` | `202 Accepted` + requestId | P99 < 100ms (DB write + Kafka produce) |
| **HTTP batch** | `POST /api/tenants/{id}/notifications/batch` | `207 Multi-Status` | P99 < 500ms (500 × ~1ms Kafka produce) |
| **Kafka-first** | Publish to `notification.requests` | No HTTP response | Async, no SLA — fire-and-forget |

"HTTP ingestion SLA" means the time for the HTTP response to return, **not** the time for
the notification to be delivered to the recipient. Delivery happens asynchronously after
the consumer picks up from Kafka.

---

## Metrics Endpoints

```
GET /actuator/health      — Service health + circuit breaker states
GET /actuator/metrics     — All Micrometer metrics (JSON, human-readable)
GET /actuator/prometheus  — Prometheus scrape endpoint (for Grafana)
```

No authentication required on actuator endpoints (configure `management.endpoints.web.exposure`
to restrict in production).

---

## Custom Metrics

All metrics are defined in `NotificationMetrics.java` and recorded in `DispatchService`.

### Counters

| Metric name | Tags | Description |
|---|---|---|
| `notification.dispatched` | `channel`, `status` | Every dispatch attempt outcome |
| `notification.dlq` | `channel` | Notifications sent to dead-letter queue |
| `notification.rate_limited` | `channel` | Dispatch attempts rejected by rate limiter |
| `notification.prerequisite_wait` | `channel` | Dispatch deferred (prerequisite not delivered) |

**Example Prometheus queries:**

```promql
# Delivery success rate per channel (last 5 min)
rate(notification_dispatched_total{status="delivered"}[5m])
  / rate(notification_dispatched_total[5m])

# Failure rate — alert if > 5%
rate(notification_dispatched_total{status="failed"}[5m])
  / rate(notification_dispatched_total[5m]) > 0.05

# DLQ rate — alert on any sustained DLQ growth
rate(notification_dlq_total[5m]) > 0

# Rate-limited requests per channel
rate(notification_rate_limited_total[5m])
```

### Timers (histograms)

| Metric name | Tags | Percentiles | Description |
|---|---|---|---|
| `notification.dispatch.duration` | `channel` | P50, P95, P99 | Wall-clock time of channel dispatch call |

**Example Prometheus queries:**

```promql
# P99 dispatch latency per channel
histogram_quantile(0.99,
  rate(notification_dispatch_duration_seconds_bucket[5m])
) by (channel)

# P50 dispatch latency (median)
histogram_quantile(0.50,
  rate(notification_dispatch_duration_seconds_bucket[5m])
) by (channel)
```

### Circuit Breaker Metrics (Resilience4j)

Automatically exposed via `management.health.circuitbreakers.enabled: true`:

```
GET /actuator/health
{
  "components": {
    "circuitBreakers": {
      "status": "UP",
      "details": {
        "email":    { "state": "CLOSED", "failureRate": "2.0%" },
        "sms":      { "state": "CLOSED", "failureRate": "0.0%" },
        "whatsapp": { "state": "OPEN",   "failureRate": "80.0%" }  ← alert here
      }
    }
  }
}
```

**Alert rule:** circuit breaker state = OPEN for > 60 seconds → paging alert.

---

## Structured Logging (MDC)

Every log line in the dispatch path includes MDC fields from two sources:

**`MdcLoggingFilter`** (HTTP requests):
- `requestId` — unique per HTTP request, echoed in `X-Request-Id` response header
- `tenantId` — from JWT principal
- `path` — `GET /api/...`

**`DispatchService`** (Kafka consumer threads):
- `notificationId`
- `tenantId`
- `channel`
- `correlationId` — present when the notification belongs to an order sequence

**Log pattern** (`application.yml`):
```
%d{yyyy-MM-dd HH:mm:ss} %5p [%X{requestId:--} %X{tenantId:--} %X{correlationId:--}] %c{1} - %m%n
```

**Example output:**
```
2026-06-22 10:00:01  INFO [req-abc123 t-uuid-1234 order-xyz-999] DispatchService - Delivered notification=... channel=EMAIL attempt=1 durationMs=23
2026-06-22 10:00:02  WARN [-- t-uuid-5678 --] DispatchService - Circuit OPEN for channel=WHATSAPP — failing fast
2026-06-22 10:00:03 ERROR [-- t-uuid-9999 --] DlqEventConsumer - DLQ: forced EXHAUSTED for notification=...
```

**Querying logs** (in Grafana Loki or Splunk):
```
# All log lines for a specific order
{app="notification-service"} | logfmt | correlationId = "order-abc-123"

# All WARN+ for a tenant
{app="notification-service"} | logfmt | tenantId = "t-uuid-1234" | level =~ "WARN|ERROR"

# Trace a specific HTTP request end-to-end
{app="notification-service"} | logfmt | requestId = "req-abc123"
```

---

## Adding Observability to New Features

When adding a new feature, follow this checklist:

- [ ] **Counter**: add a `Counter` in `NotificationMetrics` for every new terminal outcome
- [ ] **Timer**: add a `Timer` if the feature involves an external call (channel, DB, Redis)
- [ ] **MDC**: call `MDC.put("myField", value)` at the entry point of async paths (Kafka consumers)
- [ ] **Health indicator**: if the feature has an external dependency, add a `HealthIndicator` bean
- [ ] **Log level discipline**: `DEBUG` for per-notification detail, `INFO` for batch summaries, `WARN` for recoverable issues, `ERROR` for DLQ / unrecoverable

### Using the `/add-channel` skill
The `/add-channel` command already includes observability steps:
- The new channel's dispatcher records `recordDispatch` and `recordDlq`
- The channel name is used as the `channel` tag on all metrics

---

## Recommended Alerts

| Condition | Severity | Action |
|---|---|---|
| `notification_dispatched{status="exhausted"}` rate > 1%/min | P2 | Investigate provider errors or bad data in DLQ |
| `notification_dispatched{status="failed"}` rate > 10%/min | P2 | Channel provider degraded |
| Circuit breaker OPEN on any channel > 2 min | P1 | Provider outage — check channel dashboard |
| Kafka consumer lag > 10,000 on dispatch topic | P2 | Add instances or increase partitions |
| `notification_rate_limited` rate > 100/min per tenant | P3 | Tenant needs higher rate limit or has runaway traffic |
| JVM heap > 80% | P2 | Memory pressure — increase heap or add instances |
| `hikari_connections_pending` > 0 sustained | P2 | DB connection pool exhausted — add PgBouncer or reduce pool size |

---

## Grafana Dashboard (suggested panels)

```
Row: Throughput
  • Dispatch rate by channel (notifications/sec)
  • Success rate by channel (%)
  • DLQ rate

Row: Latency
  • P50 / P95 / P99 dispatch duration by channel
  • HTTP P99 ingestion latency (from Spring MVC metrics)

Row: Health
  • Circuit breaker state per channel (colour: green=CLOSED, red=OPEN)
  • Kafka consumer lag (notifications.dispatch, notifications.retry)
  • Rate-limited requests per tenant

Row: Infrastructure
  • JVM heap usage
  • Hikari active / pending connections
  • Redis operation rate
```
