package com.dmg.notification.unit;

import com.dmg.notification.controller.PlatformAdminController;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.dto.response.TenantResponse;
import com.dmg.notification.exception.GlobalExceptionHandler;
import com.dmg.notification.exception.TenantNotFoundException;
import com.dmg.notification.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class PlatformAdminControllerTest {

    @Mock TenantService tenantService;

    private MockMvc mockMvc;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PlatformAdminController(tenantService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createTenant_validRequest_returns201() throws Exception {
        TenantResponse resp = TenantResponse.builder()
                .id(tenantId).name("ACME").active(true).build();
        when(tenantService.createTenant(any())).thenReturn(resp);

        mockMvc.perform(post("/api/platform/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ACME\",\"adminEmail\":\"admin@acme.com\",\"adminPassword\":\"Password1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("ACME"));
    }

    @Test
    void listTenants_returns200WithList() throws Exception {
        when(tenantService.listTenants()).thenReturn(List.of(
                TenantResponse.builder().id(tenantId).name("ACME").active(true).build()));

        mockMvc.perform(get("/api/platform/tenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("ACME"));
    }

    @Test
    void getTenant_notFound_returns404() throws Exception {
        when(tenantService.getTenant(tenantId)).thenThrow(new TenantNotFoundException(tenantId));

        mockMvc.perform(get("/api/platform/tenants/{id}", tenantId))
                .andExpect(status().isNotFound());
    }

    @Test
    void setTenantActive_returns204() throws Exception {
        doNothing().when(tenantService).setActive(eq(tenantId), eq(false));

        mockMvc.perform(patch("/api/platform/tenants/{id}/active", tenantId)
                        .param("active", "false"))
                .andExpect(status().isNoContent());
    }

    @Test
    void upsertRateLimit_validRequest_returns200() throws Exception {
        com.dmg.notification.domain.RateLimitConfig config = com.dmg.notification.domain.RateLimitConfig.builder()
                .id(UUID.randomUUID())
                .channel(Channel.EMAIL)
                .requestsPerMinute(100)
                .requestsPerHour(5000)
                .build();
        when(tenantService.upsertRateLimit(eq(tenantId), eq(Channel.EMAIL), any())).thenReturn(config);

        mockMvc.perform(put("/api/platform/tenants/{id}/rate-limits/EMAIL", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestsPerMinute\":100,\"requestsPerHour\":5000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestsPerMinute").value(100));
    }

    @Test
    void getRateLimits_returns200() throws Exception {
        when(tenantService.getRateLimits(tenantId)).thenReturn(List.of());

        mockMvc.perform(get("/api/platform/tenants/{id}/rate-limits", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
