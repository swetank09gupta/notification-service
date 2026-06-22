Scaffold a new integration test class for a feature area.

## When to use
When adding a new feature that needs an end-to-end integration test with
real PostgreSQL + Redis + Kafka (Testcontainers).

## Template

Create `src/test/java/com/dmg/notification/integration/<FeatureName>IntegrationTest.java`:

```java
package com.dmg.notification.integration;

import com.dmg.notification.domain.Tenant;
import com.dmg.notification.domain.User;
import com.dmg.notification.domain.enums.UserRole;
import com.dmg.notification.dto.request.SendNotificationRequest;
import com.dmg.notification.repository.NotificationRepository;
import com.dmg.notification.repository.NotificationRequestRepository;
import com.dmg.notification.repository.TenantRepository;
import com.dmg.notification.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class <FeatureName>IntegrationTest extends BaseIntegrationTest {

    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired NotificationRequestRepository requestRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private UUID tenantId;
    private String tenantToken;  // if you need to call REST endpoints

    @BeforeEach
    void setup() {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("<FeatureName>Tenant-" + UUID.randomUUID())
                .apiKey(UUID.randomUUID().toString())
                .active(true)
                .build());
        tenantId = tenant.getId();

        // Uncomment if tests make HTTP calls via MockMvc
        // User admin = userRepository.save(User.builder()
        //         .email("admin-" + UUID.randomUUID() + "@test.com")
        //         .passwordHash(passwordEncoder.encode("pass123"))
        //         .role(UserRole.TENANT_ADMIN)
        //         .tenant(tenant)
        //         .build());
        // tenantToken = loginAndGetToken(admin.getEmail(), "pass123");
    }

    @Test
    void <testName>_<condition>_<expectedOutcome>() {
        // Arrange
        SendNotificationRequest req = new SendNotificationRequest();
        req.setRecipientRef("test-user");
        req.setEmail("test@example.com");
        req.setChannel(com.dmg.notification.domain.enums.Channel.EMAIL);
        req.setBody("Test message");
        req.setIdempotencyKey("test-" + UUID.randomUUID());

        // Act — call the service or publish to Kafka

        // Assert — use Awaitility for async assertions
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            // your assertions here
        });
    }
}
```

## Key patterns

### For HTTP-path tests
Use `notificationService.send(tenantId, req)` directly (no MockMvc needed since we have
the full Spring context).

### For Kafka-path tests
```java
@Autowired KafkaTemplate<String, Object> kafkaTemplate;
@Value("${app.kafka.topic-requests}") String requestsTopic;

kafkaTemplate.send(new ProducerRecord<>(requestsTopic, correlationId, event));
```

### For async assertions (always use Awaitility)
```java
await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
    List<Notification> notifications = notificationRepository.findAllByRequestId(requestId);
    assertThat(notifications).hasSize(1);
    assertThat(notifications.get(0).getStatus()).isEqualTo(NotificationStatus.DELIVERED);
});
```
Never use `Thread.sleep()` — Awaitility polls every 100ms and gives a clear failure message.

### For testing failures
Use the dispatcher test hooks:
- `"fail-transient-email:user@example.com"` → triggers a transient failure in `EmailChannelDispatcher`
- `"fail-permanent-email:user@example.com"` → triggers a permanent failure

## Checklist
- [ ] Class extends `BaseIntegrationTest`
- [ ] `@BeforeEach` creates a unique tenant per test (avoids cross-test pollution)
- [ ] All async assertions use `Awaitility`, not `Thread.sleep`
- [ ] Test names follow `<action>_<condition>_<expectedOutcome>` pattern
- [ ] `mvn test -Dtest="<FeatureName>IntegrationTest"` passes with Docker running
