package com.dmg.notification.unit;

import com.dmg.notification.controller.NotificationController;
import com.dmg.notification.domain.Tenant;
import com.dmg.notification.domain.User;
import com.dmg.notification.domain.enums.UserRole;
import com.dmg.notification.dto.response.NotificationRequestResponse;
import com.dmg.notification.exception.GlobalExceptionHandler;
import com.dmg.notification.repository.NotificationRepository;
import com.dmg.notification.security.UserPrincipal;
import com.dmg.notification.service.BatchNotificationService;
import com.dmg.notification.service.NotificationService;
import com.dmg.notification.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock NotificationService notificationService;
    @Mock BatchNotificationService batchNotificationService;
    @Mock NotificationRepository notificationRepository;
    @Mock ReportService reportService;

    private MockMvc mockMvc;
    private UUID tenantId;
    private UserPrincipal tenantPrincipal;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        Tenant tenant = Tenant.builder().id(tenantId).name("ACME").active(true).build();
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("tenant@example.com")
                .passwordHash("hash")
                .role(UserRole.TENANT_ADMIN)
                .build();
        user.setTenant(tenant);
        tenantPrincipal = new UserPrincipal(user);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new NotificationController(
                        notificationService, batchNotificationService, notificationRepository, reportService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(principalResolver(tenantPrincipal), new PageableHandlerMethodArgumentResolver())
                .build();
    }

    @Test
    void send_validRequest_returns202() throws Exception {
        NotificationRequestResponse resp = NotificationRequestResponse.builder()
                .id(UUID.randomUUID()).build();
        when(notificationService.send(eq(tenantId), any())).thenReturn(resp);

        mockMvc.perform(post("/api/tenants/{tenantId}/notifications/send", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientRef\":\"user1\",\"email\":\"u@ex.com\",\"body\":\"Hello\",\"channel\":\"EMAIL\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void send_wrongTenant_returns403() throws Exception {
        UUID otherTenant = UUID.randomUUID();

        mockMvc.perform(post("/api/tenants/{tenantId}/notifications/send", otherTenant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientRef\":\"user1\",\"body\":\"Hello\",\"channel\":\"EMAIL\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getRequest_returns200() throws Exception {
        UUID requestId = UUID.randomUUID();
        NotificationRequestResponse resp = NotificationRequestResponse.builder()
                .id(requestId).build();
        when(notificationService.getRequest(tenantId, requestId)).thenReturn(resp);

        mockMvc.perform(get("/api/tenants/{tenantId}/notifications/{requestId}", tenantId, requestId))
                .andExpect(status().isOk());
    }

    @Test
    void listRequests_returns200() throws Exception {
        when(notificationService.listRequests(eq(tenantId), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/tenants/{tenantId}/notifications", tenantId))
                .andExpect(status().isOk());
    }

    @Test
    void getDeliveries_returns200WithDtoList() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(notificationRepository.findAllByRequestId(requestId)).thenReturn(List.of());

        mockMvc.perform(get("/api/tenants/{tenantId}/notifications/{requestId}/deliveries",
                tenantId, requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getAttempts_returns200WithDtoList() throws Exception {
        UUID notificationId = UUID.randomUUID();
        when(reportService.getAttempts(notificationId)).thenReturn(List.of());

        mockMvc.perform(get("/api/tenants/{tenantId}/notifications/delivery/{notificationId}/attempts",
                tenantId, notificationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void platformAdmin_canAccessAnyTenant() throws Exception {
        UUID otherTenantId = UUID.randomUUID();
        User adminUser = User.builder()
                .id(UUID.randomUUID())
                .email("admin@platform.com")
                .passwordHash("hash")
                .role(UserRole.PLATFORM_ADMIN)
                .build();
        UserPrincipal adminPrincipal = new UserPrincipal(adminUser);

        // Rebuild MockMvc with admin principal
        MockMvc adminMvc = MockMvcBuilders
                .standaloneSetup(new NotificationController(
                        notificationService, batchNotificationService, notificationRepository, reportService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(principalResolver(adminPrincipal), new PageableHandlerMethodArgumentResolver())
                .build();

        when(notificationRepository.findAllByRequestId(any())).thenReturn(List.of());

        adminMvc.perform(get("/api/tenants/{tenantId}/notifications/{requestId}/deliveries",
                        otherTenantId, UUID.randomUUID()))
                .andExpect(status().isOk());
    }

    private HandlerMethodArgumentResolver principalResolver(UserPrincipal principal) {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                return principal;
            }
        };
    }
}
