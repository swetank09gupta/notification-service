package com.dmg.notification.service;

import com.dmg.notification.domain.RateLimitConfig;
import com.dmg.notification.domain.Tenant;
import com.dmg.notification.domain.User;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.domain.enums.UserRole;
import com.dmg.notification.dto.request.CreateTenantRequest;
import com.dmg.notification.dto.request.RateLimitConfigRequest;
import com.dmg.notification.dto.response.TenantResponse;
import com.dmg.notification.exception.TenantNotFoundException;
import com.dmg.notification.ratelimit.RateLimiterRegistry;
import com.dmg.notification.repository.RateLimitConfigRepository;
import com.dmg.notification.repository.TenantRepository;
import com.dmg.notification.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final RateLimitConfigRepository rateLimitConfigRepository;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public TenantResponse createTenant(CreateTenantRequest req) {
        if (tenantRepository.existsByName(req.getName())) {
            throw new IllegalArgumentException("Tenant name already exists: " + req.getName());
        }
        Tenant tenant = Tenant.builder()
                .name(req.getName())
                .apiKey(UUID.randomUUID().toString())
                .active(true)
                .build();
        tenant = tenantRepository.save(tenant);

        // Create the tenant admin user
        User admin = User.builder()
                .email(req.getAdminEmail())
                .passwordHash(passwordEncoder.encode(req.getAdminPassword()))
                .role(UserRole.TENANT_ADMIN)
                .tenant(tenant)
                .build();
        userRepository.save(admin);

        log.info("Tenant created id={} name={}", tenant.getId(), tenant.getName());
        return TenantResponse.from(tenant);
    }

    @Transactional(readOnly = true)
    public List<TenantResponse> listTenants() {
        return tenantRepository.findAll().stream().map(TenantResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public TenantResponse getTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .map(TenantResponse::from)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
    }

    @Transactional
    public void setActive(UUID tenantId, boolean active) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
        tenant.setActive(active);
        tenantRepository.save(tenant);
        log.info("Tenant active={} tenantId={}", active, tenantId);
    }

    @Transactional
    public RateLimitConfig upsertRateLimit(UUID tenantId, Channel channel, RateLimitConfigRequest req) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));

        RateLimitConfig config = rateLimitConfigRepository
                .findByTenantIdAndChannel(tenantId, channel)
                .orElseGet(() -> RateLimitConfig.builder().tenant(tenant).channel(channel).build());

        config.setRequestsPerMinute(req.getRequestsPerMinute());
        config.setRequestsPerHour(req.getRequestsPerHour());
        config = rateLimitConfigRepository.save(config);

        // Evict cached bucket so new limits take effect immediately
        rateLimiterRegistry.evict(tenantId, channel);
        log.info("Rate limit updated tenant={} channel={} rpm={} rph={}",
                tenantId, channel, req.getRequestsPerMinute(), req.getRequestsPerHour());
        return config;
    }

    @Transactional(readOnly = true)
    public List<RateLimitConfig> getRateLimits(UUID tenantId) {
        return rateLimitConfigRepository.findAllByTenantId(tenantId);
    }
}
