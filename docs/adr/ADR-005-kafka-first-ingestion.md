# ADR-005 — Kafka-first Ingestion (No HTTP Call Required from Upstream)

**Status:** Accepted
**Date:** 2026-06

---

## Context

The initial design required upstream services (Order Service, Payment Service, etc.) to call
`POST /api/tenants/{tenantId}/notifications/send` to request notifications. This creates
synchronous coupling:

- The upstream service must wait for our HTTP response before continuing.
- Network partitions or deployments of this service block the upstream caller.
- Under flash-sale load, upstream services amplify our HTTP tier's backpressure.

---

## Decision

Add a **Kafka-first ingestion path**: upstream services publish a `NotificationRequestEvent`
directly to the `notification.requests` topic. This service consumes the event and processes
it identically to an HTTP request.

**Both paths remain supported:**
- `source = "HTTP"`: caller used `POST /send`
- `source = "KAFKA_EVENT"`: upstream published to `notification.requests`

`NotificationRequestConsumer` converts the event to a `SendNotificationRequest` and calls
the same `NotificationService.send()` method — no duplicate business logic.

Partition key for `notification.requests` = `correlationId` (e.g., order ID). This ensures
all events for the same order go to the same partition and are processed in order.

---

## Event Schema (`NotificationRequestEvent`)

```
requestId             — idempotency key (deduplication)
tenantId              — which tenant this is for
recipientRef          — opaque recipient identifier
email / phone / whatsappNumber / deviceToken / userId  — channel-specific addresses
channels              — list of channels to dispatch on
templateId / templateName / channel  — optional template resolution
subject / body        — inline content if no template
variables             — JSON string of template substitution variables
scheduledAt           — optional future delivery time
correlationId         — groups related events (e.g., all events for order-123)
eventType             — ORDER_PLACED, ORDER_SHIPPED, ORDER_DELIVERED, etc.
dependsOnIdempotencyKey — prerequisite event that must be DELIVERED first
```

---

## Consequences

**Positive:**
- Upstream services are fully decoupled — they don't need to know this service's URL.
- `notification.requests` acts as a durable buffer; events survive restarts on both sides.
- Flash-sale burst is absorbed by Kafka lag rather than HTTP backpressure.
- Enables event-driven sequencing (ORDER_PLACED before ORDER_SHIPPED) — see ADR-006.

**Negative:**
- Upstream teams must produce to Kafka instead of calling an HTTP endpoint. Higher initial
  integration effort but lower operational coupling.
- Debugging requires Kafka tooling (`kafka-console-consumer`) in addition to HTTP logs.
- The `correlationId` field on `NotificationRequestEvent` must be set by the producer —
  the notification service cannot infer it. Document this contract with upstream teams.
