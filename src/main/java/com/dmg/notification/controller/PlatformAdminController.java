package com.dmg.notification.controller;

import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.dto.request.CreateTenantRequest;
import com.dmg.notification.dto.request.RateLimitConfigRequest;
import com.dmg.notification.dto.response.RateLimitConfigResponse;
import com.dmg.notification.dto.response.TenantResponse;
import com.dmg.notification.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/platform")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
public class PlatformAdminController {

    private final TenantService tenantService;

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse createTenant(@Valid @RequestBody CreateTenantRequest req) {
        return tenantService.createTenant(req);
    }

    @GetMapping("/tenants")
    public List<TenantResponse> listTenants() {
        return tenantService.listTenants();
    }

    @GetMapping("/tenants/{tenantId}")
    public TenantResponse getTenant(@PathVariable UUID tenantId) {
        return tenantService.getTenant(tenantId);
    }

    @PatchMapping("/tenants/{tenantId}/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setTenantActive(@PathVariable UUID tenantId,
                                 @RequestParam boolean active) {
        tenantService.setActive(tenantId, active);
    }

    @PutMapping("/tenants/{tenantId}/rate-limits/{channel}")
    public RateLimitConfigResponse upsertRateLimit(@PathVariable UUID tenantId,
                                                    @PathVariable Channel channel,
                                                    @Valid @RequestBody RateLimitConfigRequest req) {
        return RateLimitConfigResponse.from(tenantService.upsertRateLimit(tenantId, channel, req));
    }

    @GetMapping("/tenants/{tenantId}/rate-limits")
    public List<RateLimitConfigResponse> getRateLimits(@PathVariable UUID tenantId) {
        return tenantService.getRateLimits(tenantId).stream()
                .map(RateLimitConfigResponse::from).toList();
    }
}
