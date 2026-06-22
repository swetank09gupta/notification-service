package com.dmg.notification.ratelimit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe token bucket for rate limiting.
 * Tokens refill continuously based on elapsed time since last refill.
 * Two windows enforced independently: per-minute and per-hour.
 */
public class TokenBucket {

    private final long perMinuteCapacity;
    private final long perHourCapacity;

    private final AtomicLong minuteTokens;
    private final AtomicLong hourTokens;

    private volatile long lastMinuteRefillNanos;
    private volatile long lastHourRefillNanos;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    public TokenBucket(int requestsPerMinute, int requestsPerHour) {
        this.perMinuteCapacity = requestsPerMinute;
        this.perHourCapacity = requestsPerHour;
        this.minuteTokens = new AtomicLong(requestsPerMinute);
        this.hourTokens = new AtomicLong(requestsPerHour);
        long now = System.nanoTime();
        this.lastMinuteRefillNanos = now;
        this.lastHourRefillNanos = now;
    }

    /**
     * Attempts to consume one token. Returns true if allowed, false if rate-limited.
     */
    public synchronized boolean tryConsume() {
        refill();
        if (minuteTokens.get() <= 0 || hourTokens.get() <= 0) {
            return false;
        }
        minuteTokens.decrementAndGet();
        hourTokens.decrementAndGet();
        return true;
    }

    private void refill() {
        long now = System.nanoTime();

        long elapsedMinuteNanos = now - lastMinuteRefillNanos;
        if (elapsedMinuteNanos > 0) {
            // Tokens generated per nanosecond = capacity / (60 * NANOS_PER_SECOND)
            long newMinuteTokens = (elapsedMinuteNanos * perMinuteCapacity) / (60 * NANOS_PER_SECOND);
            if (newMinuteTokens > 0) {
                minuteTokens.set(Math.min(perMinuteCapacity, minuteTokens.get() + newMinuteTokens));
                lastMinuteRefillNanos = now;
            }
        }

        long elapsedHourNanos = now - lastHourRefillNanos;
        if (elapsedHourNanos > 0) {
            long newHourTokens = (elapsedHourNanos * perHourCapacity) / (3600 * NANOS_PER_SECOND);
            if (newHourTokens > 0) {
                hourTokens.set(Math.min(perHourCapacity, hourTokens.get() + newHourTokens));
                lastHourRefillNanos = now;
            }
        }
    }

    public long getMinuteTokens() { return minuteTokens.get(); }
    public long getHourTokens() { return hourTokens.get(); }
}
