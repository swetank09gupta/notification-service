package com.dmg.notification.unit;

import com.dmg.notification.kafka.DlqEventConsumer;
import com.dmg.notification.kafka.NotificationEvent;
import com.dmg.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DlqEventConsumerTest {

    @Mock NotificationService notificationService;
    @InjectMocks DlqEventConsumer consumer;

    @Test
    void notificationEvent_forcesExhausted() {
        UUID notificationId = UUID.randomUUID();
        NotificationEvent event = new NotificationEvent();
        event.setNotificationId(notificationId);
        event.setTenantId(UUID.randomUUID());

        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
                "notification.dlq", 0, 100L, "key", event);

        consumer.consume(record);

        verify(notificationService).forceExhausted(notificationId);
    }

    @Test
    void nullNotificationId_skipsForceExhausted() {
        NotificationEvent event = new NotificationEvent();
        event.setNotificationId(null);

        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
                "notification.dlq", 0, 101L, "key", event);

        consumer.consume(record);

        verifyNoInteractions(notificationService);
    }

    @Test
    void unknownPayloadType_doesNotCallService() {
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
                "notification.dlq", 0, 102L, "key", "unexpected-string-payload");

        consumer.consume(record);

        verifyNoInteractions(notificationService);
    }

    @Test
    void nullPayload_doesNotCallService() {
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
                "notification.dlq", 0, 103L, "key", null);

        consumer.consume(record);

        verifyNoInteractions(notificationService);
    }
}
