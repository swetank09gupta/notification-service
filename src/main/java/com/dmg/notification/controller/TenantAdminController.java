package com.dmg.notification.controller;

import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.dto.request.ChannelConfigRequest;
import com.dmg.notification.dto.request.TemplateRequest;
import com.dmg.notification.dto.response.ChannelConfigResponse;
import com.dmg.notification.dto.response.DeliveryReportResponse;
import com.dmg.notification.dto.response.TemplateResponse;
import com.dmg.notification.security.UserPrincipal;
import com.dmg.notification.service.ChannelConfigService;
import com.dmg.notification.service.ReportService;
import com.dmg.notification.service.TemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tenants/{tenantId}")
@RequiredArgsConstructor
public class TenantAdminController {

    private final TemplateService templateService;
    private final ReportService reportService;
    private final ChannelConfigService channelConfigService;

    // Templates

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse createTemplate(@PathVariable UUID tenantId,
                                            @Valid @RequestBody TemplateRequest req,
                                            @AuthenticationPrincipal UserPrincipal principal) {
        assertTenantAccess(principal, tenantId);
        return templateService.create(tenantId, req);
    }

    @GetMapping("/templates")
    public List<TemplateResponse> listTemplates(@PathVariable UUID tenantId,
                                                  @AuthenticationPrincipal UserPrincipal principal) {
        assertTenantAccess(principal, tenantId);
        return templateService.list(tenantId);
    }

    @GetMapping("/templates/{templateId}")
    public TemplateResponse getTemplate(@PathVariable UUID tenantId,
                                         @PathVariable UUID templateId,
                                         @AuthenticationPrincipal UserPrincipal principal) {
        assertTenantAccess(principal, tenantId);
        return templateService.get(tenantId, templateId);
    }

    @PutMapping("/templates/{templateId}")
    public TemplateResponse updateTemplate(@PathVariable UUID tenantId,
                                            @PathVariable UUID templateId,
                                            @Valid @RequestBody TemplateRequest req,
                                            @AuthenticationPrincipal UserPrincipal principal) {
        assertTenantAccess(principal, tenantId);
        return templateService.update(tenantId, templateId, req);
    }

    @DeleteMapping("/templates/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateTemplate(@PathVariable UUID tenantId,
                                    @PathVariable UUID templateId,
                                    @AuthenticationPrincipal UserPrincipal principal) {
        assertTenantAccess(principal, tenantId);
        templateService.deactivate(tenantId, templateId);
    }

    // Channel configuration

    @GetMapping("/channels")
    public List<ChannelConfigResponse> listChannelConfigs(@PathVariable UUID tenantId,
                                                           @AuthenticationPrincipal UserPrincipal principal) {
        assertTenantAccess(principal, tenantId);
        return channelConfigService.list(tenantId);
    }

    @PutMapping("/channels")
    public ChannelConfigResponse upsertChannelConfig(@PathVariable UUID tenantId,
                                                      @Valid @RequestBody ChannelConfigRequest req,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        assertTenantAccess(principal, tenantId);
        return channelConfigService.upsert(tenantId, req);
    }

    @PatchMapping("/channels/{channel}/active")
    public ChannelConfigResponse toggleChannel(@PathVariable UUID tenantId,
                                               @PathVariable Channel channel,
                                               @RequestParam boolean active,
                                               @AuthenticationPrincipal UserPrincipal principal) {
        assertTenantAccess(principal, tenantId);
        return channelConfigService.setActive(tenantId, channel, active);
    }

    // Reports

    @GetMapping("/reports")
    public DeliveryReportResponse getReport(@PathVariable UUID tenantId,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        assertTenantAccess(principal, tenantId);
        return reportService.getReport(tenantId);
    }

    private void assertTenantAccess(UserPrincipal principal, UUID tenantId) {
        boolean isPlatformAdmin = principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
        if (!isPlatformAdmin && !tenantId.equals(principal.getTenantId())) {
            throw new AccessDeniedException("Access denied to tenant: " + tenantId);
        }
    }
}
