# Domain Model

## Entity Hierarchy

```
Tenant
 ├── User (TENANT_ADMIN role)
 ├── Template (reusable message templates per channel)
 ├── ChannelConfig (enabled channels + provider config)
 ├── RateLimitConfig (per-channel rate limits)
 └── NotificationRequest (one per send/batch-item call)
       └── Notification (one per channel, one per NotificationRequest)
             └── DeliveryAttempt (one per dispatch attempt)
```

## Entities

### Tenant
The top-level isolation boundary. Every piece of data belongs to exactly one tenant.

| Field | Type | Notes |
|---|---|---|
| id | UUID | PK |
| name | String | Display name |
| apiKey | String | Unique, used for API key auth (future) |
| active | boolean | Soft disable — inactive tenants rejected at `NotificationService.send` |

### User
Represents a human operator. Scoped to a single tenant (except PLATFORM_ADMIN users who have `tenantId = null`).

| Field | Notes |
|---|---|
| role | `PLATFORM_ADMIN` or `TENANT_ADMIN` |
| tenantId | Null for platform admins |
| email | Login identifier |
| passwordHash | BCrypt |

### Template
Reusable message template owned by a tenant. Templates use `{{variableName}}` placeholders.

| Field | Notes |
|---|---|
| name | Identifier within a tenant |
| channel | Which channel this template renders for |
| subject | Email subject (nullable for SMS/WhatsApp) |
| body | Message body — may contain `{{variable}}` placeholders |
| active | Soft delete |

### ChannelConfig
Per-tenant, per-channel configuration. Controls whether a channel is enabled and stores provider-specific config (e.g. Twilio account SID, SendGrid API key) as JSON.

| Field | Notes |
|---|---|
| channel | One of EMAIL, SMS, WHATSAPP, PUSH, IN_APP |
| active | If false, dispatches to this channel are rejected |
| configJson | Provider-specific settings (future: encrypted at rest) |

### RateLimitConfig
Per-tenant, per-channel rate limit. Loaded by `RateLimiterRegistry` to configure the Redis Lua script limits.

| Field | Notes |
|---|---|
| channel | Target channel |
| maxPerMinute | Redis per-minute window limit |
| maxPerHour | Redis per-hour window limit |

### NotificationRequest
The logical send request. One request may fan out to multiple channels (and therefore multiple `Notification` rows).

| Field | Notes |
|---|---|
| idempotencyKey | UNIQUE. Callers provide this to prevent duplicate sends. |
| channels | Comma-separated channel list, e.g. `"EMAIL,SMS"` |
| status | `PENDING → PROCESSING → COMPLETED / SCHEDULED` |
| scheduledAt | If set and in future, request stays `SCHEDULED` until the poller fires |
| correlationId | Groups related requests (e.g. all notifications for order-123) |
| eventType | `ORDER_PLACED`, `ORDER_SHIPPED`, etc. |
| dependsOnIdempotencyKey | If set, dispatch waits until this request is fully DELIVERED |
| source | `HTTP` or `KAFKA_EVENT` |

### Notification
One row per channel per `NotificationRequest`. This is the unit that gets dispatched, retried, and tracked.

| Field | Notes |
|---|---|
| channel | The specific channel for this row |
| status | `PENDING → PROCESSING → DELIVERED / FAILED → EXHAUSTED / RATE_LIMITED` |
| recipientAddress | Resolved address (email, phone number, device token, userId) |
| renderedSubject | Template subject after variable substitution |
| renderedBody | Template body after variable substitution |
| attemptCount | How many dispatch attempts have been made |
| maxAttempts | Default 5; can be overridden per-request in future |
| nextRetryAt | When the retry scheduler should re-publish this row |

### DeliveryAttempt
Immutable audit log of every dispatch attempt.

| Field | Notes |
|---|---|
| attemptNumber | 1-based |
| status | `SUCCESS`, `FAILED`, `RATE_LIMITED` |
| errorMessage | Provider error message on failure |
| channelResponse | Provider acknowledgement on success |
| attemptedAt | Timestamp |
| durationMs | Wall-clock time of the provider call |

---

## Status State Machine

### NotificationRequest.status
```
PENDING → PROCESSING → COMPLETED
PENDING → SCHEDULED → PROCESSING → COMPLETED
```

### Notification.status
```
PENDING
  └─► PROCESSING
        ├─► DELIVERED           (terminal — success)
        ├─► FAILED              (transient — retry scheduled via next_retry_at)
        │     └─► PROCESSING    (retry attempt)
        │           ├─► DELIVERED
        │           └─► EXHAUSTED  (terminal — max attempts reached → DLQ)
        ├─► RATE_LIMITED        (retry scheduled — attempt count may or may not increment)
        └─► EXHAUSTED           (terminal — permanent failure or max attempts)
```

---

## Glossary

| Term | Meaning |
|---|---|
| **correlationId** | Groups all notifications for a single business entity (e.g. one order). Used as Kafka partition key for ordering. |
| **idempotencyKey** | Caller-supplied deduplication key. Same key = same request, no duplicate send. |
| **dependsOnIdempotencyKey** | Prerequisite: this notification waits until the referenced request is fully `DELIVERED`. |
| **fan-out** | One `NotificationRequest` with `channels: ["EMAIL","SMS"]` creates two `Notification` rows dispatched independently. |
| **DLQ** | Dead-letter queue (`notification.dlq`). Receives exhausted notifications for manual inspection/replay. |
| **EXHAUSTED** | Terminal failure state. Max retry attempts reached or permanent provider error. |
| **transient failure** | Provider error that may resolve on retry (timeout, 5xx). Schedules retry. |
| **permanent failure** | Provider error that will not resolve on retry (invalid recipient, 4xx). Goes directly to EXHAUSTED. |
