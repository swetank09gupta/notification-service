package com.dmg.notification.unit;

import com.dmg.notification.controller.TenantAdminController;
import com.dmg.notification.domain.ChannelConfig;
import com.dmg.notification.domain.Tenant;
import com.dmg.notification.domain.User;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.domain.enums.UserRole;
import com.dmg.notification.dto.response.ChannelConfigResponse;
import com.dmg.notification.dto.response.DeliveryReportResponse;
import com.dmg.notification.dto.response.TemplateResponse;
import com.dmg.notification.exception.GlobalExceptionHandler;
import com.dmg.notification.exception.TemplateNotFoundException;
import com.dmg.notification.security.UserPrincipal;
import com.dmg.notification.service.ChannelConfigService;
import com.dmg.notification.service.ReportService;
import com.dmg.notification.service.TemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class TenantAdminControllerTest {

    @Mock TemplateService templateService;
    @Mock ReportService reportService;
    @Mock ChannelConfigService channelConfigService;

    private MockMvc mockMvc;
    private UUID tenantId;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        Tenant tenant = Tenant.builder().id(tenantId).name("ACME").active(true).build();
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("admin@acme.com")
                .passwordHash("hash")
                .role(UserRole.TENANT_ADMIN)
                .build();
        user.setTenant(tenant);
        principal = new UserPrincipal(user);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new TenantAdminController(templateService, reportService, channelConfigService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(principalResolver(principal))
                .build();
    }

    // --- Template tests ---

    @Test
    void createTemplate_validRequest_returns201() throws Exception {
        TemplateResponse resp = TemplateResponse.builder()
                .id(UUID.randomUUID()).name("welcome").channel(Channel.EMAIL).build();
        when(templateService.create(eq(tenantId), any())).thenReturn(resp);

        mockMvc.perform(post("/api/tenants/{tenantId}/templates", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"welcome\",\"channel\":\"EMAIL\",\"body\":\"Hello {{name}}\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("welcome"));
    }

    @Test
    void listTemplates_returns200() throws Exception {
        when(templateService.list(tenantId)).thenReturn(List.of());

        mockMvc.perform(get("/api/tenants/{tenantId}/templates", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getTemplate_notFound_returns404() throws Exception {
        UUID templateId = UUID.randomUUID();
        when(templateService.get(tenantId, templateId))
                .thenThrow(new TemplateNotFoundException(templateId));

        mockMvc.perform(get("/api/tenants/{tenantId}/templates/{templateId}", tenantId, templateId))
                .andExpect(status().isNotFound());
    }

    @Test
    void deactivateTemplate_returns204() throws Exception {
        UUID templateId = UUID.randomUUID();
        doNothing().when(templateService).deactivate(tenantId, templateId);

        mockMvc.perform(delete("/api/tenants/{tenantId}/templates/{templateId}", tenantId, templateId))
                .andExpect(status().isNoContent());
    }

    // --- Channel config tests ---

    @Test
    void listChannelConfigs_returns200() throws Exception {
        when(channelConfigService.list(tenantId)).thenReturn(List.of());

        mockMvc.perform(get("/api/tenants/{tenantId}/channels", tenantId))
                .andExpect(status().isOk());
    }

    @Test
    void toggleChannel_returns200() throws Exception {
        ChannelConfig cc = ChannelConfig.builder().channel(Channel.EMAIL).active(false).build();
        when(channelConfigService.setActive(tenantId, Channel.EMAIL, false))
                .thenReturn(ChannelConfigResponse.from(cc));

        mockMvc.perform(patch("/api/tenants/{tenantId}/channels/{channel}/active", tenantId, "EMAIL")
                        .param("active", "false"))
                .andExpect(status().isOk());
    }

    // --- Report tests ---

    @Test
    void getReport_returns200() throws Exception {
        DeliveryReportResponse report = DeliveryReportResponse.builder()
                .tenantId(tenantId).totalRequests(10).delivered(8).failed(2).build();
        when(reportService.getReport(tenantId)).thenReturn(report);

        mockMvc.perform(get("/api/tenants/{tenantId}/reports", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRequests").value(10));
    }

    @Test
    void wrongTenant_returns403() throws Exception {
        UUID otherTenant = UUID.randomUUID();

        mockMvc.perform(get("/api/tenants/{tenantId}/reports", otherTenant))
                .andExpect(status().isForbidden());
    }

    private HandlerMethodArgumentResolver principalResolver(UserPrincipal p) {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                return p;
            }
        };
    }
}
