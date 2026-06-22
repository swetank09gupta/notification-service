package com.dmg.notification.unit;

import com.dmg.notification.domain.RateLimitConfig;
import com.dmg.notification.domain.Tenant;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.dto.request.CreateTenantRequest;
import com.dmg.notification.dto.request.RateLimitConfigRequest;
import com.dmg.notification.dto.response.TenantResponse;
import com.dmg.notification.exception.TenantNotFoundException;
import com.dmg.notification.ratelimit.RateLimiterRegistry;
import com.dmg.notification.repository.RateLimitConfigRepository;
import com.dmg.notification.repository.TenantRepository;
import com.dmg.notification.repository.UserRepository;
import com.dmg.notification.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock TenantRepository tenantRepository;
    @Mock UserRepository userRepository;
    @Mock RateLimitConfigRepository rateLimitConfigRepository;
    @Mock RateLimiterRegistry rateLimiterRegistry;
    @Mock PasswordEncoder passwordEncoder;

    TenantService service;
    UUID tenantId;
    Tenant tenant;

    @BeforeEach
    void setUp() {
        service = new TenantService(tenantRepository, userRepository,
                rateLimitConfigRepository, rateLimiterRegistry, passwordEncoder);
        tenantId = UUID.randomUUID();
        tenant = Tenant.builder().id(tenantId).name("Acme").apiKey("api-key").active(true).build();
    }

    @Test
    void createTenant_success_savesAndReturnsResponse() {
        when(tenantRepository.existsByName("NewCo")).thenReturn(false);
        when(tenantRepository.save(any(Tenant.class))).thenReturn(tenant);
        when(passwordEncoder.encode(any())).thenReturn("hashed");

        CreateTenantRequest req = new CreateTenantRequest();
        req.setName("NewCo");
        req.setAdminEmail("admin@newco.com");
        req.setAdminPassword("password123");

        TenantResponse resp = service.createTenant(req);

        verify(tenantRepository).save(any(Tenant.class));
        verify(userRepository).save(any());
        assertThat(resp).isNotNull();
    }

    @Test
    void createTenant_duplicateName_throwsIllegalArgument() {
        when(tenantRepository.existsByName("Existing")).thenReturn(true);

        CreateTenantRequest req = new CreateTenantRequest();
        req.setName("Existing");
        req.setAdminEmail("a@b.com");
        req.setAdminPassword("pass1234");

        assertThatThrownBy(() -> service.createTenant(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Existing");
    }

    @Test
    void listTenants_returnsAllMappedToResponse() {
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        List<TenantResponse> result = service.listTenants();
        assertThat(result).hasSize(1);
    }

    @Test
    void getTenant_notFound_throwsTenantNotFoundException() {
        UUID missing = UUID.randomUUID();
        when(tenantRepository.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getTenant(missing))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void setActive_false_deactivatesTenant() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any())).thenReturn(tenant);

        service.setActive(tenantId, false);

        ArgumentCaptor<Tenant> cap = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantRepository).save(cap.capture());
        assertThat(cap.getValue().isActive()).isFalse();
    }

    @Test
    void upsertRateLimit_createsNewConfigAndEvictsCache() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(rateLimitConfigRepository.findByTenantIdAndChannel(tenantId, Channel.EMAIL))
                .thenReturn(Optional.empty());
        RateLimitConfig saved = RateLimitConfig.builder()
                .tenant(tenant).channel(Channel.EMAIL)
                .requestsPerMinute(100).requestsPerHour(5000).build();
        when(rateLimitConfigRepository.save(any())).thenReturn(saved);

        RateLimitConfigRequest req = new RateLimitConfigRequest();
        req.setRequestsPerMinute(100);
        req.setRequestsPerHour(5000);

        RateLimitConfig result = service.upsertRateLimit(tenantId, Channel.EMAIL, req);

        assertThat(result.getRequestsPerMinute()).isEqualTo(100);
        verify(rateLimiterRegistry).evict(tenantId, Channel.EMAIL);
    }
}
