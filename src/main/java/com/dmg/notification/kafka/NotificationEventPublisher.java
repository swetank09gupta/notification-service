package com.dmg.notification.kafka;

import com.dmg.notification.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Publishes notification events to Kafka topics.
 *
 * Partitioning key = tenantId string → ensures all notifications for a tenant
 * land on the same partition, preserving ordering within a tenant.
 *
 * Flash-sale / burst scenario: Kafka absorbs the burst without back-pressure
 * on the HTTP layer. Consumer lag naturally smooths out spikes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationEventPublisher {

    private final KafkaTemplate<String, NotificationEvent> kafkaTemplate;
    private final AppProperties appProperties;

    public void publishDispatch(UUID tenantId, UUID notificationId) {
        NotificationEvent event = new NotificationEvent(notificationId, tenantId, NotificationEvent.Type.DISPATCH);
        send(appProperties.getKafka().getTopicDispatch(), tenantId.toString(), event);
    }

    public void publishRetry(UUID tenantId, UUID notificationId, int attemptCount) {
        NotificationEvent event = new NotificationEvent(notificationId, tenantId,
                NotificationEvent.Type.RETRY, attemptCount);
        send(appProperties.getKafka().getTopicRetry(), tenantId.toString(), event);
    }

    public void publishToDlq(UUID tenantId, UUID notificationId, String reason) {
        NotificationEvent event = new NotificationEvent(notificationId, tenantId, NotificationEvent.Type.RETRY);
        log.error("Publishing to DLQ: notificationId={} tenantId={} reason={}", notificationId, tenantId, reason);
        send(appProperties.getKafka().getTopicDlq(), tenantId.toString(), event);
    }

    /** Publishes a batch of dispatches; each message goes to the same dispatch topic. */
    public void publishDispatchBatch(UUID tenantId, Iterable<UUID> notificationIds) {
        for (UUID id : notificationIds) {
            publishDispatch(tenantId, id);
        }
    }

    private void send(String topic, String key, NotificationEvent event) {
        CompletableFuture<SendResult<String, NotificationEvent>> future =
                kafkaTemplate.send(topic, key, event);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish to Kafka topic={} key={} event={}", topic, key, event, ex);
            } else {
                log.debug("Published to Kafka topic={} partition={} offset={}",
                        topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
