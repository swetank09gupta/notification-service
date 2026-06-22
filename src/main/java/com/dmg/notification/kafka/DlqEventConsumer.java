package com.dmg.notification.kafka;

import com.dmg.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the Dead Letter Queue topic.
 *
 * DLQ messages arrive here in two cases:
 * 1. Business-level exhaustion: DispatchService explicitly publishes when max attempts are reached.
 * 2. Infrastructure failure: DefaultErrorHandler routes here after Kafka-level retry exhaustion.
 *
 * Actions taken:
 * - Log with full context for alerting/dashboards.
 * - Ensure DB state is EXHAUSTED (idempotent update).
 * - In production, this would also emit a metric/alert (PagerDuty, Datadog, etc.).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqEventConsumer {

    private final NotificationService notificationService;

    @KafkaListener(
            topics = "${app.kafka.topic-dlq}",
            groupId = "${spring.kafka.consumer.group-id}-dlq",
            concurrency = "1"
    )
    public void consume(ConsumerRecord<String, ?> record) {
        log.error("DLQ message received — topic={} partition={} offset={} key={}",
                record.topic(), record.partition(), record.offset(), record.key());

        Object value = record.value();
        if (value instanceof NotificationEvent event) {
            handleEvent(event);
        } else {
            log.error("DLQ: unrecognised payload type={}", value == null ? "null" : value.getClass().getName());
        }
    }

    private void handleEvent(NotificationEvent event) {
        if (event.getNotificationId() == null) {
            log.error("DLQ: event has null notificationId, skipping");
            return;
        }
        notificationService.forceExhausted(event.getNotificationId());
        log.error("DLQ: forced EXHAUSTED for notification={} tenant={}",
                event.getNotificationId(), event.getTenantId());
    }
}
