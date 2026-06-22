package com.dmg.notification.kafka;

import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.dto.request.SendNotificationRequest;
import com.dmg.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Kafka-first ingestion: consumes {@code notification.requests} published directly
 * by upstream services (Order, Payment, Shipping, etc.) — no HTTP call required.
 *
 * Partitioning: the producer MUST use {@code correlationId} as the partition key.
 * This guarantees that all notification events for the same business entity (e.g. one order)
 * land on the same partition and are processed sequentially by the same consumer thread.
 *
 *   ORDER_PLACED   → partition N  (key=order-123)
 *   ORDER_SHIPPED  → partition N  (key=order-123)  ← same partition, consumed after ORDER_PLACED
 *   ORDER_DELIVERED → partition N (key=order-123)
 *
 * The {@code dependsOnIdempotencyKey} field is an additional safety net checked at
 * dispatch time in case the async gap between ingestion and dispatch causes ordering issues.
 *
 * Error handling: relies on the shared {@code DefaultErrorHandler} (exponential backoff → DLQ).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRequestConsumer {

    private final NotificationService notificationService;

    @KafkaListener(
            topics = "${app.kafka.topic-requests}",
            groupId = "${spring.kafka.consumer.group-id}-ingest",
            concurrency = "${app.kafka.consumer-concurrency:5}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(@Payload NotificationRequestEvent event,
                        @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset) {
        log.info("Ingest event={} correlation={} partition={} offset={}",
                event, event.getCorrelationId(), partition, offset);

        SendNotificationRequest req = toRequest(event);
        try {
            notificationService.send(event.getTenantId(), req);
        } catch (Exception ex) {
            // Let DefaultErrorHandler retry; rethrow so offset is not committed
            log.error("Failed to ingest notification event={} cause={}", event, ex.getMessage());
            throw ex;
        }
    }

    private SendNotificationRequest toRequest(NotificationRequestEvent e) {
        SendNotificationRequest req = new SendNotificationRequest();
        req.setRecipientRef(e.getRecipientRef());
        req.setEmail(e.getEmail());
        req.setPhone(e.getPhone());
        req.setWhatsappNumber(e.getWhatsappNumber());
        req.setDeviceToken(e.getDeviceToken());
        req.setUserId(e.getUserId());

        if (e.getChannels() != null && !e.getChannels().isEmpty()) {
            req.setChannels(e.getChannels().stream().map(Channel::valueOf).toList());
        }
        if (StringUtils.hasText(e.getTemplateId())) {
            req.setTemplateId(UUID.fromString(e.getTemplateId()));
        }
        req.setTemplateName(e.getTemplateName());
        req.setSubject(e.getSubject());
        req.setBody(e.getBody());
        req.setVariables(e.getVariables());

        if (StringUtils.hasText(e.getScheduledAt())) {
            req.setScheduledAt(Instant.parse(e.getScheduledAt()));
        }

        // Idempotency key = requestId from the producer (stable, producer-generated)
        req.setIdempotencyKey(e.getRequestId());

        // Event-driven context
        req.setCorrelationId(e.getCorrelationId());
        req.setEventType(e.getEventType());
        req.setDependsOnIdempotencyKey(e.getDependsOnIdempotencyKey());
        req.setSource("KAFKA_EVENT");

        return req;
    }
}
