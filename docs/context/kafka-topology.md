# Kafka Topology

## Topic Map

```
Upstream Services
      │
      │  NotificationRequestEvent
      ▼  (key = correlationId)
┌─────────────────────────┐
│  notification.requests  │  10 partitions
└─────────────────────────┘
      │
      ▼
NotificationRequestConsumer (groupId: notification-service-ingest, concurrency=5)
      │  calls NotificationService.send()
      │  which persists to DB and publishes ──────────────────────────────────────┐
      │                                                                            │
      ▼  NotificationEvent (DISPATCH)                                             │
┌─────────────────────────┐                                                       │
│  notification.dispatch  │  10 partitions  (key = tenantId)                     │
└─────────────────────────┘                                                       │
      │                                                                            │
      ▼                                                                            │
NotificationDispatchConsumer (groupId: notification-service, concurrency=5)        │
      │  calls DispatchService.executeDispatch()                                   │
      │                                                                            │
      ├── DELIVERED ──────────────────────────────────────────────────────────────┤
      │                                                                            │  NotificationStatusEvent
      ├── FAILED / RATE_LIMITED                                                   │  (key = correlationId)
      │     │  (next_retry_at set in DB)                                          ▼
      │     └── NotificationScheduler (polls every 10s)            ┌─────────────────────────┐
      │               │  publishes                                  │  notification.status    │  10 partitions
      │               ▼  NotificationEvent (RETRY)                 └─────────────────────────┘
      │   ┌───────────────────────┐                                      │
      │   │  notification.retry  │  10 partitions (key = tenantId)      ▼
      │   └───────────────────────┘                              Downstream Services
      │               │                                          (Order Service, Analytics, etc.)
      │               ▼
      │   NotificationDispatchConsumer (same consumer, listens to both dispatch + retry)
      │
      └── EXHAUSTED
            │
            ▼  (key = tenantId)
┌───────────────────────┐
│  notification.dlq     │  1 partition
└───────────────────────┘
            │
            ▼
DlqEventConsumer (concurrency=1)
      └── forces EXHAUSTED in DB, logs for alerting
```

---

## Consumer Groups

| Consumer class | Group ID | Topics | Concurrency |
|---|---|---|---|
| `NotificationRequestConsumer` | `notification-service-ingest` | `notification.requests` | 5 |
| `NotificationDispatchConsumer` | `notification-service` | `notification.dispatch`, `notification.retry` | 5 |
| `DlqEventConsumer` | `notification-service` | `notification.dlq` | 1 |

---

## Message Types

### `NotificationEvent` (internal)
Used on `notification.dispatch` and `notification.retry`.
```json
{
  "notificationId": "uuid",
  "tenantId":       "uuid",
  "eventType":      "DISPATCH | RETRY",
  "attemptCount":   0
}
```

### `NotificationRequestEvent` (inbound from upstream)
Full request schema — see `docs/context/domain-model.md` for field descriptions.

### `NotificationStatusEvent` (outbound to downstream)
```json
{
  "notificationId":  "uuid",
  "requestId":       "uuid",
  "tenantId":        "uuid",
  "channel":         "EMAIL",
  "status":          "DELIVERED | FAILED | EXHAUSTED | RATE_LIMITED",
  "eventTime":       "2026-06-22T10:00:00Z",
  "attemptCount":    1,
  "errorMessage":    null,
  "correlationId":   "order-abc-123",
  "eventType":       "ORDER_PLACED",
  "idempotencyKey":  "order-abc-123-email-v1"
}
```

---

## Error Handling

The `DefaultErrorHandler` (configured in `KafkaConfig`) handles **infrastructure-level**
Kafka failures (deserialization errors, DB unavailable):

- Retries: 3 attempts with exponential backoff (1s → 4s → 16s → 30s max)
- After 3 retries: message is routed to `notification.dlq` via `DeadLetterPublishingRecoverer`
- `DeserializationException` is non-retryable (bad message format will never improve)

**Business failures** (channel dispatch failed, rate limited, circuit open) are handled
entirely inside `DispatchService` and never surface as exceptions to the Kafka listener layer.
The listener never sees a failed business result as an exception.

---

## Exactly-Once Considerations

Kafka-level exactly-once is not enabled (requires `enable.idempotence=true` + transactions).
Idempotency is handled at the application level:

1. `notification_requests.idempotency_key` UNIQUE constraint — same request key returns
   the existing request without reprocessing.
2. `status == DELIVERED` guard in `executeDispatch` — a notification already delivered
   will not be dispatched again, even if the Kafka message is re-delivered.

This provides at-least-once delivery with application-level deduplication.
