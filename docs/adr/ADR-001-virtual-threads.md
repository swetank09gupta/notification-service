# ADR-001 — Java 21 Virtual Threads over Reactive

**Status:** Accepted
**Date:** 2026-06

---

## Context

The service needs to handle high volumes of outbound I/O: HTTP calls to email/SMS/WhatsApp
providers, PostgreSQL queries, Redis ops, and Kafka produce/consume. Three concurrency models
were considered:

1. **Platform threads** (traditional Spring MVC): one OS thread per request. Blocks on I/O.
   At 200 concurrent dispatches that's 200 OS threads — workable but wasteful.

2. **Reactive (WebFlux + R2DBC)**: non-blocking, efficient under load. But requires a full
   reactive programming model across all layers — including DB queries, which significantly
   increases complexity, learning curve, and debugging difficulty.

3. **Virtual threads (Java 21, Project Loom)**: non-blocking at the OS level but synchronous
   programming model. A blocked virtual thread parks and yields its carrier thread; no OS
   thread is consumed during the I/O wait.

---

## Decision

Use **Java 21 virtual threads** via `spring.threads.virtual.enabled: true`.

This makes every Spring-managed thread (HTTP handler threads, Kafka consumer threads,
scheduler threads, Hikari connection threads) a virtual thread automatically, with zero
code changes to the existing imperative style.

---

## Consequences

**Positive:**
- Familiar, debuggable imperative code with `@Transactional`, blocking JDBC, blocking Kafka — unchanged.
- No reactive learning curve or reactive testing complexity.
- Hikari pool size of 20 can now serve hundreds of concurrent virtual threads because blocked
  threads don't hold OS threads.
- Stack traces are readable — no reactive chain of lambdas.

**Negative:**
- Virtual threads should not be pinned (blocked on `synchronized` blocks). Avoid heavy use of
  `synchronized` in hot paths. Prefer `java.util.concurrent.locks.ReentrantLock`.
- Requires Java 21+. The team must not downgrade the JDK.
- `ThreadLocal` still works but be careful with thread-local state that assumes one thread
  per request — virtual threads are reused across requests after yielding.

**Invariant:** Never remove `spring.threads.virtual.enabled: true` from `application.yml`
without updating this ADR.
