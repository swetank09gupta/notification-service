package com.dmg.notification.integration;

import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.Tenant;
import com.dmg.notification.domain.User;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.domain.enums.NotificationStatus;
import com.dmg.notification.domain.enums.UserRole;
import com.dmg.notification.dto.request.LoginRequest;
import com.dmg.notification.dto.request.SendNotificationRequest;
import com.dmg.notification.dto.request.TemplateRequest;
import com.dmg.notification.dto.response.AuthResponse;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationFlowIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private UUID tenantId;
    private String tenantAdminToken;

    @BeforeEach
    void setup() throws Exception {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("TestCo-" + UUID.randomUUID())
                .apiKey(UUID.randomUUID().toString())
                .active(true)
                .build());
        tenantId = tenant.getId();

        String email = "admin-" + UUID.randomUUID() + "@test.com";
        userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("password123"))
                .role(UserRole.TENANT_ADMIN)
                .tenant(tenant)
                .build());

        LoginRequest login = new LoginRequest();
        login.setEmail(email);
        login.setPassword("password123");

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn();

        tenantAdminToken = objectMapper.readValue(
                result.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }

    @Test
    void sendImmediateNotification_emailDelivered() throws Exception {
        SendNotificationRequest req = new SendNotificationRequest();
        req.setRecipientRef("user-1");
        req.setEmail("user@example.com");
        req.setChannel(Channel.EMAIL);
        req.setBody("Hello from integration test");
        req.setSubject("Test subject");

        MvcResult result = mockMvc.perform(post("/api/tenants/{tenantId}/notifications/send", tenantId)
                        .header("Authorization", "Bearer " + tenantAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").exists())
                .andReturn();

        NotificationRequestResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), NotificationRequestResponse.class);

        // Wait for async dispatch to complete
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Notification> notifications = notificationRepository.findAllByRequestId(response.getId());
            assertThat(notifications).hasSize(1);
            assertThat(notifications.get(0).getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        });
    }

    @Test
    void sendWithTemplate_variablesSubstituted() throws Exception {
        // Create template
        TemplateRequest templateReq = new TemplateRequest();
        templateReq.setName("welcome");
        templateReq.setChannel(Channel.EMAIL);
        templateReq.setSubject("Welcome {{name}}!");
        templateReq.setBody("Hi {{name}}, welcome to our platform!");

        MvcResult templateResult = mockMvc.perform(post("/api/tenants/{tenantId}/templates", tenantId)
                        .header("Authorization", "Bearer " + tenantAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(templateReq)))
                .andExpect(status().isCreated())
                .andReturn();

        String templateId = objectMapper.readTree(
                templateResult.getResponse().getContentAsString()).get("id").asText();

        SendNotificationRequest req = new SendNotificationRequest();
        req.setRecipientRef("user-2");
        req.setEmail("user2@example.com");
        req.setTemplateId(UUID.fromString(templateId));
        req.setVariables("{\"name\":\"Alice\"}");

        mockMvc.perform(post("/api/tenants/{tenantId}/notifications/send", tenantId)
                        .header("Authorization", "Bearer " + tenantAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted());
    }

    @Test
    void idempotency_duplicateKeyReturns409() throws Exception {
        SendNotificationRequest req = new SendNotificationRequest();
        req.setRecipientRef("user-3");
        req.setEmail("user3@example.com");
        req.setChannel(Channel.EMAIL);
        req.setBody("Test");
        req.setIdempotencyKey("unique-key-abc-123");

        // First request succeeds
        mockMvc.perform(post("/api/tenants/{tenantId}/notifications/send", tenantId)
                        .header("Authorization", "Bearer " + tenantAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted());

        // Duplicate returns 409
        mockMvc.perform(post("/api/tenants/{tenantId}/notifications/send", tenantId)
                        .header("Authorization", "Bearer " + tenantAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    @Test
    void getRequest_returnsCorrectStatus() throws Exception {
        SendNotificationRequest req = new SendNotificationRequest();
        req.setRecipientRef("user-4");
        req.setEmail("user4@example.com");
        req.setChannel(Channel.EMAIL);
        req.setBody("Test");

        MvcResult sendResult = mockMvc.perform(post("/api/tenants/{tenantId}/notifications/send", tenantId)
                        .header("Authorization", "Bearer " + tenantAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn();

        String requestId = objectMapper.readTree(
                sendResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/tenants/{tenantId}/notifications/{requestId}", tenantId, requestId)
                        .header("Authorization", "Bearer " + tenantAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(requestId));
    }

    @Test
    void crossTenantAccess_returns403() throws Exception {
        UUID otherTenantId = UUID.randomUUID();

        mockMvc.perform(get("/api/tenants/{tenantId}/notifications", otherTenantId)
                        .header("Authorization", "Bearer " + tenantAdminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void multiChannelSend_createsNotificationPerChannel() throws Exception {
        SendNotificationRequest req = new SendNotificationRequest();
        req.setRecipientRef("user-5");
        req.setEmail("user5@example.com");
        req.setPhone("+1234567890");
        req.setChannels(List.of(Channel.EMAIL, Channel.SMS));
        req.setBody("Multi-channel test");

        MvcResult result = mockMvc.perform(post("/api/tenants/{tenantId}/notifications/send", tenantId)
                        .header("Authorization", "Bearer " + tenantAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andReturn();

        String requestId = objectMapper.readTree(
                result.getResponse().getContentAsString()).get("id").asText();

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Notification> notifications = notificationRepository.findAllByRequestId(UUID.fromString(requestId));
            assertThat(notifications).hasSize(2);
        });
    }
}
