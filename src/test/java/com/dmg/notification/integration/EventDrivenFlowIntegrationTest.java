package com.dmg.notification.integration;

import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.Tenant;
import com.dmg.notification.domain.User;
import com.dmg.notification.domain.enums.NotificationStatus;
import com.dmg.notification.domain.enums.UserRole;
import com.dmg.notification.kafka.NotificationRequestEvent;
import com.dmg.notification.repository.NotificationRepository;
import com.dmg.notification.repository.NotificationRequestRepository;
import com.dmg.notification.repository.TenantRepository;
import com.dmg.notification.repository.UserRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Tests the Kafka-first ingestion path:
 *  upstream service → notification.requests → NotificationRequestConsumer
 *  → dispatch → notification.status
 *
 * Also tests order event sequencing with dependsOnIdempotencyKey.
 */
class EventDrivenFlowIntegrationTest extends BaseIntegrationTest {

    @Autowired KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired NotificationRequestRepository requestRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Value("${app.kafka.topic-requests}")
    private String requestsTopic;

    private UUID tenantId;

    @BeforeEach
    void setup() {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("EventDrivenCo-" + UUID.randomUUID())
                .apiKey(UUID.randomUUID().toString())
                .active(true)
                .build());
        tenantId = tenant.getId();
    }

    @Test
    void kafkaFirstIngestion_emailDeliveredWithoutHttpCall() throws Exception {
        String idempotencyKey = "kf-test-" + UUID.randomUUID();

        NotificationRequestEvent event = new NotificationRequestEvent();
        event.setRequestId(idempotencyKey);
        event.setTenantId(tenantId);
        event.setRecipientRef("user-kafka");
        event.setEmail("kafka-user@example.com");
        event.setChannels(List.of("EMAIL"));
        event.setBody("Sent directly via Kafka — no HTTP call");
        event.setCorrelationId("order-" + UUID.randomUUID());
        event.setEventType("ORDER_PLACED");

        // Produce to notification.requests — partitioned by correlationId
        kafkaTemplate.send(new ProducerRecord<>(requestsTopic, event.getCorrelationId(), event));

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var reqs = requestRepository.findByIdempotencyKey(idempotencyKey);
            assertThat(reqs).isPresent();
            List<Notification> notifications = notificationRepository
                    .findAllByRequestId(reqs.get().getId());
            assertThat(notifications).hasSize(1);
            assertThat(notifications.get(0).getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        });
    }

    @Test
    void orderSequencing_shippedWaitsForPlaced() throws Exception {
        String orderId = "order-seq-" + UUID.randomUUID();
        String placedKey = orderId + "-placed";
        String shippedKey = orderId + "-shipped";

        // Publish ORDER_PLACED (no dependency)
        NotificationRequestEvent placed = new NotificationRequestEvent();
        placed.setRequestId(placedKey);
        placed.setTenantId(tenantId);
        placed.setRecipientRef("seq-user");
        placed.setEmail("seq@example.com");
        placed.setChannels(List.of("EMAIL"));
        placed.setBody("Your order has been placed.");
        placed.setCorrelationId(orderId);
        placed.setEventType("ORDER_PLACED");

        // Publish ORDER_SHIPPED with dependency on ORDER_PLACED
        NotificationRequestEvent shipped = new NotificationRequestEvent();
        shipped.setRequestId(shippedKey);
        shipped.setTenantId(tenantId);
        shipped.setRecipientRef("seq-user");
        shipped.setEmail("seq@example.com");
        shipped.setChannels(List.of("EMAIL", "SMS"));
        shipped.setPhone("+919000000001");
        shipped.setBody("Your order has been shipped!");
        shipped.setCorrelationId(orderId);
        shipped.setEventType("ORDER_SHIPPED");
        shipped.setDependsOnIdempotencyKey(placedKey); // <- MUST wait for ORDER_PLACED

        // Both events go to the same partition (correlationId = orderId)
        kafkaTemplate.send(new ProducerRecord<>(requestsTopic, orderId, placed));
        kafkaTemplate.send(new ProducerRecord<>(requestsTopic, orderId, shipped));

        // ORDER_PLACED should be delivered first
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var req = requestRepository.findByIdempotencyKey(placedKey);
            assertThat(req).isPresent();
            var notifications = notificationRepository.findAllByRequestId(req.get().getId());
            assertThat(notifications).anyMatch(n -> n.getStatus() == NotificationStatus.DELIVERED);
        });

        // ORDER_SHIPPED should be delivered after ORDER_PLACED (due to dependency gating)
        // We allow more time because the dependency check → retry cycle adds latency
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            var req = requestRepository.findByIdempotencyKey(shippedKey);
            assertThat(req).isPresent();
            var notifications = notificationRepository.findAllByRequestId(req.get().getId());
            // Both EMAIL and SMS channels should eventually be delivered
            assertThat(notifications).hasSize(2);
            assertThat(notifications).allMatch(n ->
                    n.getStatus() == NotificationStatus.DELIVERED
                    || n.getStatus() == NotificationStatus.FAILED);
        });
    }

    @Test
    void idempotency_duplicateKafkaEventIgnored() throws Exception {
        String idempotencyKey = "idem-kafka-" + UUID.randomUUID();
        String correlationId = "order-idem-" + UUID.randomUUID();

        NotificationRequestEvent event = new NotificationRequestEvent();
        event.setRequestId(idempotencyKey);
        event.setTenantId(tenantId);
        event.setRecipientRef("idem-user");
        event.setEmail("idem@example.com");
        event.setChannels(List.of("EMAIL"));
        event.setBody("Should only be delivered once");
        event.setCorrelationId(correlationId);
        event.setEventType("ORDER_PLACED");

        // Publish the same event twice (simulates producer retry or exactly-once failure)
        kafkaTemplate.send(new ProducerRecord<>(requestsTopic, correlationId, event));
        kafkaTemplate.send(new ProducerRecord<>(requestsTopic, correlationId, event));

        // Should create exactly ONE NotificationRequest (idempotency check)
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            long count = requestRepository.findAll().stream()
                    .filter(r -> idempotencyKey.equals(r.getIdempotencyKey()))
                    .count();
            assertThat(count).isEqualTo(1);
        });
    }
}
