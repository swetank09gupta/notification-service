# ADR-004 — Resilience4j Circuit Breakers per Channel

**Status:** Accepted
**Date:** 2026-06

---

## Context

External channel providers (email relay, SMS gateway, WhatsApp Business API) can go down
or degrade independently. Without isolation, a WhatsApp outage causes every WhatsApp dispatch
attempt to time out, consuming Kafka consumer threads and causing backlog on all channels.

Two approaches were considered:

1. **Timeout-only**: each dispatch has a hard timeout (e.g. 10s). When a provider is down,
   every attempt runs for the full timeout before being rescheduled. Under load this exhausts
   the consumer pool.

2. **Circuit breaker**: after observing N% failures, the circuit opens and subsequent calls
   fail immediately ("fast fail") without attempting the network call. The notification is
   rescheduled via retry — it is not lost.

---

## Decision

Use **Resilience4j `CircuitBreakerRegistry`** with one circuit breaker per channel, keyed
by lowercase channel name (`"email"`, `"sms"`, `"whatsapp"`, `"push"`, `"in_app"`).

Configuration (from `application.yml`):
- Sliding window: COUNT_BASED, last 10 calls, minimum 5 to start evaluating
- Open at: 50% failure rate
- Open wait: 60s (email/SMS/push/in_app), 120s (WhatsApp — longer outages expected)
- Half-open probe calls: 3 (2 for WhatsApp)

When the circuit is OPEN, `CallNotPermittedException` is caught in `DispatchService` and
treated as a transient failure — the notification is rescheduled without incrementing
`attemptCount` beyond what's fair.

Circuit breaker state is **programmatic** (no AOP/proxy). `circuitBreakerRegistry.circuitBreaker(name)`
is called directly in `DispatchService.executeDispatch`. This avoids Spring proxy pitfalls
with `@Transactional` + `@CircuitBreaker` co-located on the same method.

---

## Consequences

**Positive:**
- A WhatsApp outage does not affect email/SMS throughput.
- Consumers are freed immediately when the circuit is open — no timeout wait.
- `management.health.circuitbreakers.enabled: true` exposes circuit state via `/actuator/health`.
- Per-channel tuning: WhatsApp gets a longer recovery window than in-app.

**Negative:**
- Circuit state is **in-process** per instance. In a multi-instance deployment, instance A
  might open its WhatsApp circuit while instance B still attempts calls. This is acceptable:
  each instance learns independently, and the circuit opens fast (5 calls minimum).
- To share circuit state across instances: use Resilience4j `RedisRegistry` (not yet implemented).
  Add an ADR entry if this becomes a requirement.

**Invariant:** Circuit breaker keys must match the `instances:` block in `application.yml`
exactly (lowercase). Using `notification.getChannel().name()` returns uppercase — always call
`.toLowerCase()` when constructing the key.
