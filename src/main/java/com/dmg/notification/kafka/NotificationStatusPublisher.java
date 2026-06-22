package com.dmg.notification.kafka;

import com.dmg.notification.config.AppProperties;
import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.enums.NotificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes notification delivery outcomes to the {@code notification.status} topic.
 *
 * Who consumes this:
 *  - Order Service: knows when customer is notified, can gate next workflow step
 *  - Analytics Service: measures delivery rates per channel / tenant
 *  - Support Dashboard: shows real-time delivery status per order
 *
 * Partition key = correlationId (if present) else tenantId.
 * This guarantees that all status updates for the same order arrive in order
 * at the consuming service — important for state machine transitions.
 *
 * Published on EVERY meaningful status change, not just terminal states:
 *   DELIVERED  — customer reached on this channel
 *   EXHAUSTED  — max retries reached; trigger fallback in the caller
 *   FAILED     — transient failure, will retry; caller can show "in progress"
 *   RATE_LIMITED / WAITING — temporary hold; no action needed from caller
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationStatusPublisher {

    private final KafkaTemplate<String, NotificationStatusEvent> kafkaTemplate;
    private final AppProperties appProperties;

    public void publishStatus(Notification notification, NotificationStatus status, String errorMessage) {
        String correlationId = notification.getRequest() != null
                ? notification.getRequest().getCorrelationId()
                : null;
        String eventType = notification.getRequest() != null
                ? notification.getRequest().getEventType()
                : null;
        String idempotencyKey = notification.getRequest() != null
                ? notification.getRequest().getIdempotencyKey()
                : null;
        String requestId = notification.getRequest() != null
                ? notification.getRequest().getId().toString()
                : null;

        // Partition key: correlationId (orderId) if present, else tenantId
        // → ensures per-order ordering at the consuming service
        String partitionKey = correlationId != null
                ? correlationId
                : notification.getTenant().getId().toString();

        NotificationStatusEvent event = NotificationStatusEvent.from(
                notification.getId(),
                requestId != null ? java.util.UUID.fromString(requestId) : null,
                notification.getTenant().getId(),
                notification.getChannel().name(),
                status.name(),
                notification.getAttemptCount(),
                errorMessage,
                correlationId,
                eventType,
                idempotencyKey
        );

        kafkaTemplate.send(appProperties.getKafka().getTopicStatus(), partitionKey, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish status event notificationId={} status={}",
                                notification.getId(), status, ex);
                    } else {
                        log.debug("Published status notificationId={} status={} correlation={}",
                                notification.getId(), status, correlationId);
                    }
                });
    }
}
