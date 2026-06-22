package com.dmg.notification.observability;

import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.domain.enums.NotificationStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central Micrometer metrics registry for notification dispatch.
 *
 * Exposed at: GET /actuator/prometheus  (Prometheus scrape endpoint)
 *             GET /actuator/metrics     (JSON, human-readable)
 *
 * Key metrics:
 *   notification.dispatched   — total dispatch attempts, tagged by channel + status
 *   notification.dispatch.duration — histogram of dispatch wall-clock time per channel
 *   notification.dlq          — count of notifications sent to DLQ
 *   notification.rate_limited — count of rate-limit rejections, tagged by channel
 */
@Component
public class NotificationMetrics {

    private static final String METRIC_DISPATCHED    = "notification.dispatched";
    private static final String METRIC_DURATION      = "notification.dispatch.duration";
    private static final String METRIC_DLQ           = "notification.dlq";
    private static final String METRIC_RATE_LIMITED  = "notification.rate_limited";
    private static final String METRIC_PREREQUISITE  = "notification.prerequisite_wait";

    private final MeterRegistry registry;

    // Cache counters to avoid re-registration on hot path
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer>   timerCache   = new ConcurrentHashMap<>();

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Call after every dispatch attempt completes (success or failure). */
    public void recordDispatch(Channel channel, NotificationStatus outcome, long durationMs) {
        String key = channel.name() + "." + outcome.name();
        counterCache.computeIfAbsent(METRIC_DISPATCHED + "." + key, k ->
                Counter.builder(METRIC_DISPATCHED)
                       .tag("channel", channel.name().toLowerCase())
                       .tag("status", outcome.name().toLowerCase())
                       .description("Total notification dispatch attempts")
                       .register(registry)
        ).increment();

        timerCache.computeIfAbsent(METRIC_DURATION + "." + channel.name(), k ->
                Timer.builder(METRIC_DURATION)
                     .tag("channel", channel.name().toLowerCase())
                     .description("Dispatch wall-clock time per channel (ms)")
                     .publishPercentiles(0.5, 0.95, 0.99)
                     .register(registry)
        ).record(Duration.ofMillis(durationMs));
    }

    /** Call when a notification is published to DLQ after exhausting retries. */
    public void recordDlq(Channel channel) {
        counterCache.computeIfAbsent(METRIC_DLQ + "." + channel.name(), k ->
                Counter.builder(METRIC_DLQ)
                       .tag("channel", channel.name().toLowerCase())
                       .description("Notifications sent to dead-letter queue")
                       .register(registry)
        ).increment();
    }

    /** Call when a notification is skipped due to rate limiting. */
    public void recordRateLimited(Channel channel) {
        counterCache.computeIfAbsent(METRIC_RATE_LIMITED + "." + channel.name(), k ->
                Counter.builder(METRIC_RATE_LIMITED)
                       .tag("channel", channel.name().toLowerCase())
                       .description("Dispatch attempts rejected by rate limiter")
                       .register(registry)
        ).increment();
    }

    /** Call when dispatch is deferred because a prerequisite is not yet DELIVERED. */
    public void recordPrerequisiteWait(Channel channel) {
        counterCache.computeIfAbsent(METRIC_PREREQUISITE + "." + channel.name(), k ->
                Counter.builder(METRIC_PREREQUISITE)
                       .tag("channel", channel.name().toLowerCase())
                       .description("Dispatch deferred waiting for prerequisite notification")
                       .register(registry)
        ).increment();
    }
}
