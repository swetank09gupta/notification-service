package com.dmg.notification.unit;

import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.domain.enums.NotificationStatus;
import com.dmg.notification.observability.NotificationMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMetricsTest {

    MeterRegistry registry;
    NotificationMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new NotificationMetrics(registry);
    }

    @Test
    void recordDispatch_incrementsDispatchedCounterAndRecordsTimer() {
        metrics.recordDispatch(Channel.EMAIL, NotificationStatus.DELIVERED, 50L);

        double count = registry.counter("notification.dispatched",
                "channel", "email", "status", "delivered").count();
        assertThat(count).isEqualTo(1.0);

        long timerCount = registry.timer("notification.dispatch.duration", "channel", "email").count();
        assertThat(timerCount).isEqualTo(1);
    }

    @Test
    void recordDispatch_multipleCalls_countsAccumulate() {
        metrics.recordDispatch(Channel.EMAIL, NotificationStatus.DELIVERED, 10L);
        metrics.recordDispatch(Channel.EMAIL, NotificationStatus.DELIVERED, 20L);
        metrics.recordDispatch(Channel.EMAIL, NotificationStatus.FAILED, 15L);

        assertThat(registry.counter("notification.dispatched",
                "channel", "email", "status", "delivered").count()).isEqualTo(2.0);
        assertThat(registry.counter("notification.dispatched",
                "channel", "email", "status", "failed").count()).isEqualTo(1.0);
    }

    @Test
    void recordDlq_incrementsDlqCounter() {
        metrics.recordDlq(Channel.SMS);
        assertThat(registry.counter("notification.dlq", "channel", "sms").count()).isEqualTo(1.0);
    }

    @Test
    void recordRateLimited_incrementsRateLimitedCounter() {
        metrics.recordRateLimited(Channel.WHATSAPP);
        assertThat(registry.counter("notification.rate_limited", "channel", "whatsapp").count())
                .isEqualTo(1.0);
    }

    @Test
    void recordPrerequisiteWait_incrementsPrerequisiteCounter() {
        metrics.recordPrerequisiteWait(Channel.PUSH);
        assertThat(registry.counter("notification.prerequisite_wait", "channel", "push").count())
                .isEqualTo(1.0);
    }

    @Test
    void recordDispatch_multipleChannels_metricsAreTaggedPerChannel() {
        metrics.recordDispatch(Channel.EMAIL, NotificationStatus.DELIVERED, 10L);
        metrics.recordDispatch(Channel.SMS, NotificationStatus.DELIVERED, 20L);

        assertThat(registry.counter("notification.dispatched",
                "channel", "email", "status", "delivered").count()).isEqualTo(1.0);
        assertThat(registry.counter("notification.dispatched",
                "channel", "sms", "status", "delivered").count()).isEqualTo(1.0);
    }
}
