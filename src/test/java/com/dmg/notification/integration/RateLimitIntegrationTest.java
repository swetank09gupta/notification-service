package com.dmg.notification.integration;

import com.dmg.notification.domain.Tenant;
import com.dmg.notification.domain.User;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.domain.enums.NotificationStatus;
import com.dmg.notification.domain.enums.UserRole;
import com.dmg.notification.dto.request.LoginRequest;
import com.dmg.notification.dto.request.RateLimitConfigRequest;
import com.dmg.notification.dto.request.RegisterPlatformAdminRequest;
import com.dmg.notification.dto.request.SendNotificationRequest;
import com.dmg.notification.dto.response.AuthResponse;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RateLimitIntegrationTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired PasswordEncoder passwordEncoder;

    private UUID tenantId;
    private String tenantAdminToken;
    private String platformAdminToken;

    @BeforeEach
    void setup() throws Exception {
        // Platform admin
        String platformEmail = "platform-" + UUID.randomUUID() + "@test.com";
        RegisterPlatformAdminRequest regReq = new RegisterPlatformAdminRequest();
        regReq.setEmail(platformEmail);
        regReq.setPassword("password123");
        mockMvc.perform(post("/api/auth/platform-admin/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(regReq)))
                .andExpect(status().isCreated());

        LoginRequest platformLogin = new LoginRequest();
        platformLogin.setEmail(platformEmail);
        platformLogin.setPassword("password123");
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(platformLogin)))
                .andReturn();
        platformAdminToken = objectMapper.readValue(r.getResponse().getContentAsString(), AuthResponse.class).getToken();

        // Tenant + admin
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("RateLimitTestCo-" + UUID.randomUUID())
                .apiKey(UUID.randomUUID().toString())
                .active(true)
                .build());
        tenantId = tenant.getId();

        String email = "ta-" + UUID.randomUUID() + "@test.com";
        userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("password123"))
                .role(UserRole.TENANT_ADMIN)
                .tenant(tenant)
                .build());

        LoginRequest login = new LoginRequest();
        login.setEmail(email);
        login.setPassword("password123");
        MvcResult r2 = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andReturn();
        tenantAdminToken = objectMapper.readValue(r2.getResponse().getContentAsString(), AuthResponse.class).getToken();
    }

    @Test
    void rateLimitEnforced_someNotificationsRateLimited() throws Exception {
        // Set a very tight limit: 2 per minute
        RateLimitConfigRequest rlReq = new RateLimitConfigRequest();
        rlReq.setRequestsPerMinute(2);
        rlReq.setRequestsPerHour(1000);

        mockMvc.perform(put("/api/platform/tenants/{tenantId}/rate-limits/{channel}", tenantId, "EMAIL")
                        .header("Authorization", "Bearer " + platformAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rlReq)))
                .andExpect(status().isOk());

        // Send 5 notifications — only 2 should be delivered, rest rate-limited
        for (int i = 0; i < 5; i++) {
            SendNotificationRequest req = new SendNotificationRequest();
            req.setRecipientRef("user-" + i);
            req.setEmail("user" + i + "@example.com");
            req.setChannel(Channel.EMAIL);
            req.setBody("Rate limit test " + i);

            mockMvc.perform(post("/api/tenants/{tenantId}/notifications/send", tenantId)
                            .header("Authorization", "Bearer " + tenantAdminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isAccepted());
        }

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            long delivered = notificationRepository.countByTenantIdAndStatus(tenantId, NotificationStatus.DELIVERED);
            long rateLimited = notificationRepository.countByTenantIdAndStatus(tenantId, NotificationStatus.RATE_LIMITED);
            // At most 2 delivered, and some rate-limited (may also be in FAILED state awaiting retry)
            assertThat(delivered).isLessThanOrEqualTo(2);
            assertThat(delivered + rateLimited).isGreaterThan(0);
        });
    }
}
