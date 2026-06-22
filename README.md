# Multi-tenant Notification Service

A production-grade, multi-tenant notification service built with Java 21, Spring Boot 3.3, Kafka, PostgreSQL, and Redis.

Designed for high-throughput flash-sale scenarios — HTTP tier always returns `202` immediately; async Kafka pipeline handles dispatch, retries, and DLQ at scale.

---

## Architecture

```
HTTP Layer  (always returns 202 immediately)
────────────────────────────────────────────

POST /api/tenants/{id}/notifications/send
        │
        ├─ [Idempotency Check] ─(duplicate)─▶ 409 Conflict
        ├─ [Tenant active?]     ─(inactive) ─▶ 404 TenantNotFoundException
        ├─ [Channel resolve]
        │
        ▼
[NotificationService]
  ├── persist NotificationRequest (DB)
  ├── create per-channel Notification rows
  └── publish NotificationEvent ──▶ notification.dispatch topic
                                      (partition key = tenantId)
         └── return 202 Accepted


POST /api/tenants/{id}/notifications/batch  (up to 500 items)
        │
        ▼
[BatchNotificationService]
  └── each item: same idempotency + persist + Kafka publish
  └── partial success → 207 Multi-Status with per-item ACCEPTED/REJECTED


Kafka Async Pipeline
────────────────────

  notification.dispatch  ──┐
  notification.retry     ──┘  (partitioned by tenantId → per-tenant ordering)
        │
        ▼
[NotificationDispatchConsumer]  concurrency = 5 virtual threads
        │
        ├─▶ Guard: status == DELIVERED?  ─yes─▶ skip (duplicate-delivery guard)
        ├─▶ Guard: prerequisite delivered? ─no─▶ reschedule without incrementing attempt
        │
        ├─▶ [RateLimiterRegistry].tryConsume(tenantId, channel)
        │         ├─ PRIMARY:  Redis Lua script — atomic dual-window (per-min + per-hr)
        │         └─ FALLBACK: in-memory TokenBucket (if Redis unreachable)
        │         └─ EXCEEDED  ─▶ status=RATE_LIMITED, attemptCount++, retry later
        │
        ├─▶ [CircuitBreaker] (Resilience4j, per channel)
        │         ├─ CLOSED   → dispatch proceeds
        │         ├─ OPEN     → fail fast as TRANSIENT → schedule retry
        │         └─ HALF_OPEN → probe; success → CLOSED, failure → OPEN
        │
        ├─▶ [ChannelDispatcher].dispatch(notification)
        │         └─ Email / SMS / WhatsApp / Push / InApp
        │
        └─▶ persist DeliveryAttempt  (full audit trail)
                │
                ├─ SUCCESS   ──▶ status=DELIVERED
                ├─ TRANSIENT ──▶ status=FAILED, next_retry_at=now+backoff
                └─ PERMANENT ──▶ status=EXHAUSTED + publish to notification.dlq


Retry / Schedule Pollers  (every 10 s)
──────────────────────────────────────

retryPoller:
  SELECT * FROM notifications WHERE status=FAILED AND next_retry_at ≤ now
  └── publish each to notification.retry

schedulePoller:
  SELECT * FROM notification_requests WHERE status=SCHEDULED AND scheduled_at ≤ now
  └── create Notification rows + publish to notification.dispatch


Dead Letter Queue
─────────────────

notification.dlq  (concurrency = 1)
  └── [DlqEventConsumer]: force status=EXHAUSTED in DB, log error for alerting


Exponential Retry Backoff
──────────────────────────

  Attempt 1  → wait  30 s
  Attempt 2  → wait   2 min
  Attempt 3  → wait   8 min
  Attempt 4  → wait  32 min
  Attempt 5  → EXHAUSTED + DLQ  (total window ≈ 2.5 h)
```

---

## Key Design Decisions

### Java 21 Virtual Threads
`spring.threads.virtual.enabled=true` — all Spring-managed threads (HTTP handlers, Kafka consumers, schedulers) run on virtual threads. High I/O concurrency without OS-thread starvation.

### Kafka Async Pipeline
HTTP tier never blocks waiting for channel delivery. The `202` response is returned after the Kafka produce call completes (~1–2 ms). A flash-sale spike of 1 M notifications drains in ~3.5 min at 5-instance deployment.

- **Durability**: in-flight notifications survive app restarts (Kafka offsets committed after DB write)
- **Ordering**: `tenantId` partition key ensures per-tenant FIFO
- **DLQ**: `notification.dlq` + `DlqEventConsumer` ensure no notification is silently lost

### Distributed Rate Limiting (Redis + Lua)
Atomic Lua script checks and increments both per-minute and per-hour counters in a single round-trip. Falls back gracefully to in-memory `TokenBucket` when Redis is unavailable.

### Resilience4j Circuit Breaker per Channel
Each channel has its own circuit breaker. Email going down doesn't affect SMS. Circuit-open state is treated as a transient failure — retried via the scheduler when the breaker resets, not hammered continuously.

### Multi-channel Fan-out
`POST /send` with `"channels": ["EMAIL", "SMS", "WHATSAPP"]` creates three independent `Notification` rows, each with its own status/attempts/audit trail, dispatched concurrently as separate Kafka events.

### Idempotency
Unique index on `notification_requests.idempotency_key`. Duplicates return `409 Conflict` with the existing request ID. In batch mode, duplicates appear as `REJECTED` items in the `207` response without blocking the rest of the batch.

### JWT + RBAC + Tenant Isolation
Stateless JWT tokens carry `role` and `tenantId` claims. Every controller validates tenant access before touching data. Every DB query that touches tenant-owned data includes `tenantId` in the WHERE clause.

---

## Stack

| Layer | Technology | Version |
|---|---|---|
| Runtime | Java (Project Loom) | 21 |
| Framework | Spring Boot | 3.3.5 |
| Database | PostgreSQL + Flyway | 16 + 10.x |
| Messaging | Apache Kafka | 3.x |
| Rate limiting | Redis + Lua script | 7.x |
| Circuit breaker | Resilience4j | 2.x |
| Auth | JJWT | 0.12 |
| Observability | Micrometer + Prometheus | — |
| Testing | JUnit 5 + Mockito + Testcontainers | — |

---

## Roles

| Role | Capabilities |
|---|---|
| `PLATFORM_ADMIN` | Register tenants, set global rate limits, view all tenant data |
| `TENANT_ADMIN` | Manage own templates & channels, send notifications, view own reports |

---

## API Reference

### Authentication

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/auth/platform-admin/register` | None | Register first platform admin |
| `POST` | `/api/auth/login` | None | Returns JWT token |

**Login request:**
```json
{ "email": "admin@platform.com", "password": "Secure1pass" }
```
**Login response:**
```json
{ "token": "eyJ...", "role": "PLATFORM_ADMIN", "tenantId": null }
```

---

### Platform Admin  `Authorization: Bearer <token>`

| Method | Path | Response | Description |
|---|---|---|---|
| `POST` | `/api/platform/tenants` | `201` | Create tenant + admin user |
| `GET` | `/api/platform/tenants` | `200` | List all tenants |
| `GET` | `/api/platform/tenants/{id}` | `200` | Get one tenant |
| `PATCH` | `/api/platform/tenants/{id}/active?active=false` | `204` | Activate / deactivate tenant |
| `PUT` | `/api/platform/tenants/{id}/rate-limits/{channel}` | `200` | Set channel rate limit |
| `GET` | `/api/platform/tenants/{id}/rate-limits` | `200` | Get all channel limits |

**Create tenant request:**
```json
{
  "name": "Acme Corp",
  "adminEmail": "admin@acme.com",
  "adminPassword": "Acme1pass"
}
```
Password rules: 8–128 chars, must contain uppercase + lowercase + digit.

---

### Notifications  `Authorization: Bearer <token>`

| Method | Path | Response | Description |
|---|---|---|---|
| `POST` | `/api/tenants/{id}/notifications/send` | `202` | Send single notification |
| `POST` | `/api/tenants/{id}/notifications/batch` | `207` | Send up to 500 notifications |
| `GET` | `/api/tenants/{id}/notifications` | `200` | List requests (paginated) |
| `GET` | `/api/tenants/{id}/notifications/{requestId}` | `200` | Get one request |
| `GET` | `/api/tenants/{id}/notifications/{requestId}/deliveries` | `200` | Per-channel notifications |
| `GET` | `/api/tenants/{id}/notifications/delivery/{notifId}/attempts` | `200` | Delivery attempt audit trail |

**Send request body:**
```json
{
  "recipientRef": "user-123",

  "email":         "user@example.com",
  "phone":         "+919876543210",
  "whatsappNumber":"+919876543210",
  "deviceToken":   "fcm-token-abc",
  "userId":        "user-123",

  "templateId":    "uuid",
  "templateName":  "order_shipped",
  "channel":       "EMAIL",
  "channels":      ["EMAIL", "SMS"],

  "subject":       "Your order shipped!",
  "body":          "Inline body (used when no template)",

  "variables":     "{\"name\":\"Alice\",\"orderId\":\"ORD-123\"}",

  "scheduledAt":   "2026-06-24T10:00:00Z",
  "idempotencyKey":"order-123-email-v1",
  "correlationId": "order-123"
}
```

**Send response (202):**
```json
{
  "id": "uuid",
  "tenantId": "uuid",
  "status": "PROCESSING",
  "channels": ["EMAIL"],
  "createdAt": "2026-06-22T12:00:00Z"
}
```

**Batch request body:**
```json
{
  "notifications": [
    { "recipientRef": "u1", "email": "u1@co.com", "channel": "EMAIL", "body": "Hi", "idempotencyKey": "k1" },
    { "recipientRef": "u2", "phone": "+911234567890", "channel": "SMS", "body": "Hi", "idempotencyKey": "k2" }
  ]
}
```

**Batch response (207):**
```json
{
  "total": 2, "accepted": 2, "rejected": 0,
  "results": [
    { "index": 0, "status": "ACCEPTED", "requestId": "uuid", "idempotencyKey": "k1" },
    { "index": 1, "status": "REJECTED", "errorCode": "DUPLICATE_REQUEST" }
  ]
}
```

---

### Tenant Admin  `Authorization: Bearer <token>`

| Method | Path | Response | Description |
|---|---|---|---|
| `POST` | `/api/tenants/{id}/templates` | `201` | Create template |
| `GET` | `/api/tenants/{id}/templates` | `200` | List active templates |
| `GET` | `/api/tenants/{id}/templates/{tmplId}` | `200` | Get one template |
| `PUT` | `/api/tenants/{id}/templates/{tmplId}` | `200` | Update template (increments version) |
| `DELETE` | `/api/tenants/{id}/templates/{tmplId}` | `204` | Deactivate template |
| `GET` | `/api/tenants/{id}/channels` | `200` | List channel configs |
| `PUT` | `/api/tenants/{id}/channels` | `200` | Upsert channel config |
| `PATCH` | `/api/tenants/{id}/channels/{channel}/active?active=` | `200` | Enable/disable channel |
| `GET` | `/api/tenants/{id}/reports` | `200` | Delivery report summary |

---

### Validation Error Response (400)

All invalid request bodies return a structured `400` with per-field detail:
```json
{
  "status": 400,
  "error": "Validation Failed",
  "message": "Request validation failed",
  "timestamp": "2026-06-22T12:00:00Z",
  "fieldErrors": {
    "email": "email must be a valid email address",
    "adminPassword": "Password must contain at least one uppercase letter, one lowercase letter, and one digit",
    "phone": "phone must be in E.164 format e.g. +919876543210"
  }
}
```

### Validation Rules Summary

| Field | Rule |
|---|---|
| Email fields | RFC-5321 format, max 320 chars |
| Passwords | 8–128 chars, uppercase + lowercase + digit required |
| Tenant name | Max 255 chars |
| Template name | Max 255 chars |
| Template body | Max 50,000 chars |
| Notification body | Max 10,000 chars |
| Notification subject | Max 500 chars |
| Phone / WhatsApp | E.164 format: `+[country][6-14 digits]` |
| idempotencyKey | Max 255 chars |
| Batch size | 1–500 items |
| Rate limit RPM | 1–100,000 |
| Rate limit RPH | 1–1,000,000 |

---

### HTTP Status Codes

| Code | When |
|---|---|
| `200 OK` | Successful GET / PUT / PATCH |
| `201 Created` | Tenant or template created |
| `202 Accepted` | Notification accepted and queued |
| `204 No Content` | Tenant active toggled, template deactivated |
| `207 Multi-Status` | Batch — check per-item status |
| `400 Bad Request` | Validation failure (field errors included) |
| `401 Unauthorized` | Missing or expired JWT |
| `403 Forbidden` | Token valid but wrong role or wrong tenant |
| `404 Not Found` | Tenant, template, or notification not found |
| `409 Conflict` | Duplicate idempotency key |
| `500 Internal Server Error` | Unexpected error (logged with trace ID) |

---

## Running Locally

### Docker Compose (recommended)

```bash
# Start all services: PostgreSQL + Redis + Kafka + Zookeeper + App
docker-compose up --build

# App available at http://localhost:8080
# Prometheus metrics: http://localhost:8080/actuator/prometheus
# Health check:       http://localhost:8080/actuator/health
```

### Manual (pre-existing infra)

```bash
# Start only infra
docker-compose up postgres redis zookeeper kafka -d

# Run app
DB_HOST=localhost REDIS_HOST=localhost KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
  mvn spring-boot:run
```

### Quick Start Walkthrough

```bash
BASE=http://localhost:8080

# 1. Register platform admin
curl -s -X POST $BASE/api/auth/platform-admin/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@platform.com","password":"Platform1pass"}'

# 2. Get platform admin token
TOKEN=$(curl -s -X POST $BASE/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@platform.com","password":"Platform1pass"}' | jq -r .token)

# 3. Create a tenant
RESP=$(curl -s -X POST $BASE/api/platform/tenants \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Acme","adminEmail":"acme@admin.com","adminPassword":"Acme1pass"}')
TENANT_ID=$(echo $RESP | jq -r .id)

# 4. Get tenant admin token
TENANT_TOKEN=$(curl -s -X POST $BASE/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"acme@admin.com","password":"Acme1pass"}' | jq -r .token)

# 5. Send multi-channel flash-sale notification (EMAIL + SMS + WhatsApp)
REQUEST_ID=$(curl -s -X POST "$BASE/api/tenants/$TENANT_ID/notifications/send" \
  -H "Authorization: Bearer $TENANT_TOKEN" -H 'Content-Type: application/json' \
  -d '{
    "recipientRef":  "user-1",
    "email":         "user@example.com",
    "phone":         "+919876543210",
    "whatsappNumber":"+919876543210",
    "channels":      ["EMAIL","SMS","WHATSAPP"],
    "subject":       "Flash sale — 50% off!",
    "body":          "Sale starts now. Shop at example.com",
    "idempotencyKey":"flash-sale-user1-v1"
  }' | jq -r .id)

# 6. Check delivery status
curl -s "$BASE/api/tenants/$TENANT_ID/notifications/$REQUEST_ID/deliveries" \
  -H "Authorization: Bearer $TENANT_TOKEN" | jq .

# 7. Batch send to 500 users
curl -s -X POST "$BASE/api/tenants/$TENANT_ID/notifications/batch" \
  -H "Authorization: Bearer $TENANT_TOKEN" -H 'Content-Type: application/json' \
  -d '{
    "notifications": [
      {"recipientRef":"u1","email":"u1@co.com","channel":"EMAIL","body":"Sale!","idempotencyKey":"sale-u1"},
      {"recipientRef":"u2","email":"u2@co.com","channel":"EMAIL","body":"Sale!","idempotencyKey":"sale-u2"}
    ]
  }' | jq .

# 8. Delivery report
curl -s "$BASE/api/tenants/$TENANT_ID/reports" \
  -H "Authorization: Bearer $TENANT_TOKEN" | jq .
```

---

## Running Tests

```bash
# Unit tests only — no Docker required (166 tests, ~15 s)
mvn test -Dsurefire.excludes="**/integration/**"

# Single unit test class
mvn test -Dtest="DispatchServiceTest"

# Full suite with integration tests — requires Docker (Testcontainers: PG + Redis + Kafka)
# On macOS with Docker Desktop, set the socket first:
export DOCKER_HOST=unix:///var/run/docker.sock
mvn verify

# Coverage report without integration tests
mvn verify -Dsurefire.excludes="**/integration/**"
# Open: target/site/jacoco/index.html
```

### Coverage

| Scope | Tests | Line | Branch |
|---|---|---|---|
| Unit tests only (no Docker) | 166 | ~69% | ~61% |
| Unit + Integration tests (Docker) | 171+ | ≥80% | ≥75% |

JaCoCo enforces 80% line / 75% branch on `mvn verify`. Excluded from coverage check: `*Application.class`, `dto/**`, `domain/enums/**`, `*Exception.class`.

The gap between unit-only and full coverage comes from Kafka config, publishers, consumers, Redis rate-limiter, and SecurityConfig — all of which are wired end-to-end in the Testcontainers integration tests.

---

## Observability

| Endpoint | Description |
|---|---|
| `GET /actuator/health` | Readiness probe (no auth required) |
| `GET /actuator/prometheus` | Prometheus scrape endpoint (no auth required) |

### Custom Metrics

| Metric | Type | Tags |
|---|---|---|
| `notification.sent` | Counter | `tenant`, `channel` |
| `notification.dispatch.duration` | Timer | `tenant`, `channel`, `status` |
| `notification.retry` | Counter | `tenant`, `channel` |
| `notification.dlq` | Counter | `tenant`, `channel` |

### MDC Structured Logging

Every log line carries: `requestId` (UUID per HTTP request), `tenantId`, `notificationId` (in dispatch path).

```
2026-06-22 12:00:00 INFO [abc123 tenant-uuid notif-uuid] c.d.n.s.DispatchService - Dispatched successfully
```

---

## Production Scale

| Deployment | HTTP RPS | Notifications/sec | Daily capacity |
|---|---|---|---|
| 1 instance | ~500 | ~1,000 | ~86 M |
| 5 instances (recommended) | ~2,500 | ~5,000 | ~430 M |
| 20 instances (max / 20 partitions) | ~10,000 | ~20,000 | ~1.7 B |

Flash-sale burst of 1 M notifications drains in ~3.5 min at 5-instance deployment.

---

## Assumptions & Production TODOs

1. **Channel stubs** — all five dispatchers log to stdout. Replace with JavaMailSender, Twilio, WhatsApp Business API, FCM in production.

2. **Circuit breaker state** — Resilience4j state is in-memory per instance. For multi-instance consistency, use Resilience4j's Redis-backed registry.

3. **Kafka replication** — docker-compose uses `replication.factor=1`. Production: 3+ replicas, `min.insync.replicas=2`.

4. **Rate limit windows** — fixed windows (epoch minute/hour). Burst at boundary allows ~2× for a brief moment. Use a Redis sorted-set sliding window for stricter enforcement.

5. **Recipient resolution** — `email`/`phone`/`whatsappNumber` are passed inline. Production: look them up from a user-profile service.

6. **In-app channel** — stored in DB; fetched via API. Production: push via WebSocket or SSE.

7. **Secret rotation** — `JWT_SECRET` env var. Rotate via rolling deploy with short token TTL.

8. **Password complexity** — enforced at API layer (uppercase + lowercase + digit, 8–128 chars). Consider adding bcrypt work-factor tuning for prod (`BCryptPasswordEncoder(12)`).
