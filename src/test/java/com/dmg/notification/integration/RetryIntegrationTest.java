package com.dmg.notification.integration;

import com.dmg.notification.domain.Tenant;
import com.dmg.notification.domain.User;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.domain.enums.NotificationStatus;
import com.dmg.notification.domain.enums.UserRole;
import com.dmg.notification.dto.request.LoginRequest;
import com.dmg.notification.dto.request.SendNotificationRequest;
import com.dmg.notification.dto.response.AuthResponse;
import com.dmg.notification.dto.response.NotificationRequestResponse;
import com.dmg.notification.repository.DeliveryAttemptRepository;
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

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RetryIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired DeliveryAttemptRepository deliveryAttemptRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private UUID tenantId;
    private String token;

    @BeforeEach
    void setup() throws Exception {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("RetryTestCo-" + UUID.randomUUID())
                .apiKey(UUID.randomUUID().toString())
                .active(true)
                .build());
        tenantId = tenant.getId();

        String email = "retry-" + UUID.randomUUID() + "@test.com";
        userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("password123"))
                .role(UserRole.TENANT_ADMIN)
                .tenant(tenant)
                .build());

        LoginRequest login = new LoginRequest();
        login.setEmail(email);
        login.setPassword("password123");
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andReturn();
        token = objectMapper.readValue(r.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }

    @Test
    void transientFailure_deliveryAttemptPersistedWithError() throws Exception {
        // "fail-transient@" prefix triggers transient failure in stub dispatcher
        SendNotificationRequest req = new SendNotificationRequest();
        req.setRecipientRef("user-fail");
        req.setEmail("fail-transient@example.com");
        req.setChannel(Channel.EMAIL);
        req.setBody("This will fail transiently");

        MvcResult result = mockMvc.perform(post("/api/tenants/{tenantId}/notifications/send", tenantId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn();

        NotificationRequestResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), NotificationRequestResponse.class);

        // After first attempt: should be FAILED with a delivery attempt recorded
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var notifications = notificationRepository.findAllByRequestId(response.getId());
            assertThat(notifications).hasSize(1);
            var n = notifications.get(0);
            assertThat(n.getStatus()).isIn(NotificationStatus.FAILED, NotificationStatus.EXHAUSTED);
            assertThat(n.getAttemptCount()).isGreaterThanOrEqualTo(1);

            var attempts = deliveryAttemptRepository.findAllByNotificationIdOrderByAttemptNumberAsc(n.getId());
            assertThat(attempts).isNotEmpty();
            assertThat(attempts.get(0).getErrorMessage()).contains("simulated");
        });
    }

    @Test
    void permanentFailure_noRetryScheduled() throws Exception {
        SendNotificationRequest req = new SendNotificationRequest();
        req.setRecipientRef("user-perm-fail");
        req.setEmail("fail-permanent@example.com");
        req.setChannel(Channel.EMAIL);
        req.setBody("This will permanently fail");

        MvcResult result = mockMvc.perform(post("/api/tenants/{tenantId}/notifications/send", tenantId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn();

        NotificationRequestResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), NotificationRequestResponse.class);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var notifications = notificationRepository.findAllByRequestId(response.getId());
            assertThat(notifications).hasSize(1);
            var n = notifications.get(0);
            // Permanent failure: status EXHAUSTED, no next_retry_at
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.EXHAUSTED);
            assertThat(n.getNextRetryAt()).isNull();
        });
    }
}
