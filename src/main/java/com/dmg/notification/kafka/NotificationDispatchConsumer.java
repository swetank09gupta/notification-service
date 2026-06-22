package com.dmg.notification.kafka;

import com.dmg.notification.service.DispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes notification dispatch and retry events from Kafka.
 *
 * Concurrency model:
 * - spring.kafka.listener.concurrency=5 → 5 concurrent consumer threads
 * - spring.threads.virtual.enabled=true → each thread is a virtual thread (Java 21)
 * - Partition affinity: tenantId is the partition key → events for a tenant land on the
 *   same partition, processed by the same consumer thread → per-tenant ordering.
 *
 * Back-pressure under flash-sale load:
 * - HTTP layer immediately returns 202 after publishing to Kafka.
 * - Consumer lag absorbs the burst; throughput is bounded by concurrency × channel latency.
 * - Adding more instances scales horizontally (Kafka rebalances partitions automatically).
 *
 * Failure handling:
 * - Business failures (FAILED/EXHAUSTED) are handled inside executeDispatch; the listener
 *   always returns normally → Kafka commits the offset.
 * - Infrastructure failures (DB down, deserialization error) propagate as exceptions →
 *   DefaultErrorHandler retries then sends to DLQ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatchConsumer {

    private final DispatchService dispatchService;

    @KafkaListener(
            topics = {"${app.kafka.topic-dispatch}", "${app.kafka.topic-retry}"},
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(@Payload NotificationEvent event,
                        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset) {
        log.info("Consuming notificationId={} topic={} partition={} offset={}",
                event.getNotificationId(), topic, partition, offset);
        dispatchService.executeDispatch(event.getNotificationId());
    }
}
