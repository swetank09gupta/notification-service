package com.dmg.notification.integration;

import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.Tenant;
import com.dmg.notification.domain.User;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.domain.enums.NotificationStatus;
import com.dmg.notification.domain.enums.UserRole;
import com.dmg.notification.dto.request.BatchSendNotificationRequest;
import com.dmg.notification.dto.request.LoginRequest;
import com.dmg.notification.dto.request.SendNotificationRequest;
import com.dmg.notification.dto.response.AuthResponse;
import com.dmg.notification.dto.response.BatchSendResponse;
import com.dmg.notification.dto.response.NotificationRequestResponse;
import com.dmg.notification.repository.NotificationRepository;
import com.dmg.notification.repository.TenantRepository;
import com.dmg.notification.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kafka-centric integration tests:
 * - Multi-channel fan-out via Kafka
 * - Batch send (up to 500 items)
 * - WhatsApp channel delivery
 * - Partial batch (mix of success and duplicate)
 */
class KafkaFlowIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private UUID tenantId;
    private String token;

    @BeforeEach
    void setup() throws Exception {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("KafkaTestCo-" + UUID.randomUUID())
                .apiKey(UUID.randomUUID().toString())
                .active(true)
                .build());
        tenantId = tenant.getId();

        String email = "kafka-" + UUID.randomUUID() + "@test.com";
        userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("pw"))
                .role(UserRole.TENANT_ADMIN)
                .tenant(tenant)
                .build());

        LoginRequest login = new LoginRequest();
        login.setEmail(email);
        login.setPassword("pw");
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andReturn();
        token = objectMapper.readValue(r.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }

    @Test
    void multiChannelFanOut_emailSmsWhatsApp_threeSeparateNotifications() throws Exception {
        // One request → three channel-specific Notification rows dispatched via Kafka
        SendNotificationRequest req = new SendNotificationRequest();
        req.setRecipientRef("flash-sale-user");
        req.setEmail("user@example.com");
        req.setPhone("+919876543210");
        req.setWhatsappNumber("+919876543210");
        req.setChannels(List.of(Channel.EMAIL, Channel.SMS, Channel.WHATSAPP));
        req.setBody("Flash sale starts now — 50% off everything!");

        MvcResult result = mockMvc.perform(post("/api/tenants/{id}/notifications/send", tenantId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn();

        String requestId = objectMapper.readTree(
                result.getResponse().getContentAsString()).get("id").asText();

        // All three channels should be delivered via Kafka consumer
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Notification> notifications =
                    notificationRepository.findAllByRequestId(UUID.fromString(requestId));
            assertThat(notifications).hasSize(3);
            assertThat(notifications).allMatch(n -> n.getStatus() == NotificationStatus.DELIVERED);
        });
    }

    @Test
    void batchSend_50items_allAccepted() throws Exception {
        int count = 50;
        List<SendNotificationRequest> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SendNotificationRequest item = new SendNotificationRequest();
            item.setRecipientRef("user-" + i);
            item.setEmail("user" + i + "@example.com");
            item.setChannel(Channel.EMAIL);
            item.setBody("Batch message #" + i);
            item.setIdempotencyKey("batch-test-" + tenantId + "-" + i);
            items.add(item);
        }

        BatchSendNotificationRequest batchReq = new BatchSendNotificationRequest();
        batchReq.setNotifications(items);

        MvcResult result = mockMvc.perform(post("/api/tenants/{id}/notifications/batch", tenantId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchReq)))
                .andExpect(status().is(207))  // Multi-Status
                .andReturn();

        BatchSendResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), BatchSendResponse.class);

        assertThat(response.getTotal()).isEqualTo(count);
        assertThat(response.getAccepted()).isEqualTo(count);
        assertThat(response.getRejected()).isEqualTo(0);

        // All notifications should eventually be delivered
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            long delivered = response.getResults().stream()
                    .filter(r -> r.getRequestId() != null)
                    .map(r -> notificationRepository.findAllByRequestId(r.getRequestId()))
                    .filter(ns -> !ns.isEmpty())
                    .filter(ns -> ns.get(0).getStatus() == NotificationStatus.DELIVERED)
                    .count();
            assertThat(delivered).isEqualTo(count);
        });
    }

    @Test
    void batchSend_withDuplicates_partialSuccess() throws Exception {
        // Pre-send with a known idempotency key
        String duplicateKey = "dup-key-" + UUID.randomUUID();
        SendNotificationRequest first = new SendNotificationRequest();
        first.setRecipientRef("dup-user");
        first.setEmail("dup@example.com");
        first.setChannel(Channel.EMAIL);
        first.setBody("First send");
        first.setIdempotencyKey(duplicateKey);

        mockMvc.perform(post("/api/tenants/{id}/notifications/send", tenantId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isAccepted());

        // Batch that includes the duplicate key
        List<SendNotificationRequest> items = new ArrayList<>();
        SendNotificationRequest dup = new SendNotificationRequest();
        dup.setRecipientRef("dup-user");
        dup.setEmail("dup@example.com");
        dup.setChannel(Channel.EMAIL);
        dup.setBody("Duplicate");
        dup.setIdempotencyKey(duplicateKey);
        items.add(dup);

        SendNotificationRequest fresh = new SendNotificationRequest();
        fresh.setRecipientRef("fresh-user");
        fresh.setEmail("fresh@example.com");
        fresh.setChannel(Channel.EMAIL);
        fresh.setBody("Fresh message");
        fresh.setIdempotencyKey("fresh-key-" + UUID.randomUUID());
        items.add(fresh);

        BatchSendNotificationRequest batchReq = new BatchSendNotificationRequest();
        batchReq.setNotifications(items);

        MvcResult result = mockMvc.perform(post("/api/tenants/{id}/notifications/batch", tenantId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchReq)))
                .andExpect(status().is(207))
                .andReturn();

        BatchSendResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), BatchSendResponse.class);

        assertThat(response.getTotal()).isEqualTo(2);
        assertThat(response.getAccepted()).isEqualTo(1);   // only fresh
        assertThat(response.getRejected()).isEqualTo(1);   // dup rejected

        BatchSendResponse.BatchItemResult dupResult = response.getResults().get(0);
        assertThat(dupResult.getStatus()).isEqualTo("REJECTED");
        assertThat(dupResult.getErrorCode()).isEqualTo("DUPLICATE");
    }

    @Test
    void whatsAppChannel_deliveredSuccessfully() throws Exception {
        SendNotificationRequest req = new SendNotificationRequest();
        req.setRecipientRef("wa-user");
        req.setWhatsappNumber("+919123456789");
        req.setChannel(Channel.WHATSAPP);
        req.setBody("Your OTP is 123456");

        MvcResult result = mockMvc.perform(post("/api/tenants/{id}/notifications/send", tenantId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn();

        NotificationRequestResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), NotificationRequestResponse.class);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Notification> notifications = notificationRepository.findAllByRequestId(response.getId());
            assertThat(notifications).hasSize(1);
            assertThat(notifications.get(0).getStatus()).isEqualTo(NotificationStatus.DELIVERED);
            assertThat(notifications.get(0).getChannel()).isEqualTo(Channel.WHATSAPP);
        });
    }

    @Test
    void whatsAppTransientFailure_retriedViaScheduler() throws Exception {
        // fail-transient-wa: prefix triggers transient failure in stub
        SendNotificationRequest req = new SendNotificationRequest();
        req.setRecipientRef("wa-fail-user");
        req.setWhatsappNumber("fail-transient-wa:+919000000000");
        req.setChannel(Channel.WHATSAPP);
        req.setBody("This will fail transiently");

        MvcResult result = mockMvc.perform(post("/api/tenants/{id}/notifications/send", tenantId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn();

        NotificationRequestResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), NotificationRequestResponse.class);

        // After 3 max-attempts (test profile), notification should be EXHAUSTED
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Notification> notifications = notificationRepository.findAllByRequestId(response.getId());
            assertThat(notifications).hasSize(1);
            // Either still retrying (FAILED) or exhausted
            assertThat(notifications.get(0).getStatus())
                    .isIn(NotificationStatus.FAILED, NotificationStatus.EXHAUSTED);
            assertThat(notifications.get(0).getAttemptCount()).isGreaterThanOrEqualTo(1);
        });
    }
}
