# ADR-003 — Redis Lua for Distributed Rate Limiting

**Status:** Accepted
**Date:** 2026-06

---

## Context

Per-tenant rate limits must be enforced consistently across all application instances.
An in-memory `TokenBucket` (originally implemented) works correctly for a single instance
but breaks in a multi-instance deployment: each instance has its own bucket, so the
effective rate limit is `configuredLimit × instanceCount`.

Three distributed approaches were considered:

1. **DB-backed counter**: `UPDATE rate_limit_counters SET count = count + 1 WHERE ...`
   — serializable, but adds DB round-trip latency to every notification dispatch.

2. **Redis `INCR` with TTL** (two calls): not atomic — a race between `INCR` and `EXPIRE`
   can leave a key without a TTL, silently removing the rate limit forever.

3. **Redis Lua script** (single atomic call): Lua executes atomically on the Redis server.
   Both the check and increment are a single command from Redis's perspective.

---

## Decision

Use a **Redis Lua script** that atomically implements a dual fixed-window counter:
one per-minute window and one per-hour window.

```lua
local m_count = tonumber(redis.call('INCR', KEYS[1]))
if m_count == 1 then redis.call('EXPIRE', KEYS[1], 62) end
local h_count = tonumber(redis.call('INCR', KEYS[2]))
if h_count == 1 then redis.call('EXPIRE', KEYS[2], 3602) end
if m_count > tonumber(ARGV[1]) or h_count > tonumber(ARGV[2]) then
    redis.call('DECR', KEYS[1])
    redis.call('DECR', KEYS[2])
    return 0
end
return 1
```

Keys: `rl:{tenantId}:{channel}:m:{epochMinute}` and `rl:{tenantId}:{channel}:h:{epochHour}`.

**Fallback**: if Redis is unavailable (connection error, timeout), `RateLimiterRegistry`
falls back to the per-process in-memory `TokenBucket`. Rate limiting degrades gracefully to
per-instance enforcement rather than failing the dispatch attempt.

---

## Consequences

**Positive:**
- Atomic: no race conditions across instances.
- O(1) space per window; old windows expire automatically via TTL.
- Two-dimension enforcement: burst protection (per minute) + sustained-rate protection (per hour).
- Single Redis round trip per dispatch attempt.

**Negative:**
- Fixed-window allows a burst of up to `2 × perMinuteLimit` across a window boundary
  (last second of minute N and first second of minute N+1). A sliding window (sorted set)
  would be stricter but at O(n) space and two commands per request.
- Redis becomes a dependency for correct multi-instance rate limiting. The in-memory
  fallback means one node going solo after a Redis partition allows excess traffic.

**Future option**: Replace fixed window with Redis sorted set sliding window if burst
tolerance at window boundaries becomes a product concern. Update this ADR.
