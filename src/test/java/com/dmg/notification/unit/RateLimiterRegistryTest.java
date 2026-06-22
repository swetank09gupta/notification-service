package com.dmg.notification.unit;

import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.ratelimit.RateLimiterRegistry;
import com.dmg.notification.repository.RateLimitConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests the in-memory (fallback) path. Redis tests are covered in RateLimitIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterRegistryTest {

    @Mock RateLimitConfigRepository rateLimitConfigRepository;

    RateLimiterRegistry registry;
    UUID tenantId;

    @BeforeEach
    void setUp() {
        // No Redis injected → always uses in-memory fallback
        registry = new RateLimiterRegistry(rateLimitConfigRepository);
        tenantId = UUID.randomUUID();
    }

    @Test
    void tryConsume_withinDefaultLimit_allowsRequests() {
        when(rateLimitConfigRepository.findByTenantIdAndChannel(tenantId, Channel.EMAIL))
                .thenReturn(Optional.empty()); // uses defaults: 60/min, 1000/hr

        assertThat(registry.tryConsume(tenantId, Channel.EMAIL)).isTrue();
    }

    @Test
    void tryConsume_separateTenantsHaveIndependentBuckets() {
        UUID tenant2 = UUID.randomUUID();
        when(rateLimitConfigRepository.findByTenantIdAndChannel(any(), any()))
                .thenReturn(Optional.empty());

        assertThat(registry.tryConsume(tenantId, Channel.SMS)).isTrue();
        assertThat(registry.tryConsume(tenant2, Channel.SMS)).isTrue();
    }

    @Test
    void tryConsume_separateChannelsHaveIndependentBuckets() {
        when(rateLimitConfigRepository.findByTenantIdAndChannel(any(), any()))
                .thenReturn(Optional.empty());

        assertThat(registry.tryConsume(tenantId, Channel.EMAIL)).isTrue();
        assertThat(registry.tryConsume(tenantId, Channel.SMS)).isTrue();
    }

    @Test
    void evict_removesInMemoryBucketSoItIsReloadedOnNextCall() {
        when(rateLimitConfigRepository.findByTenantIdAndChannel(tenantId, Channel.EMAIL))
                .thenReturn(Optional.empty());

        registry.tryConsume(tenantId, Channel.EMAIL); // creates bucket — 1 load
        registry.evict(tenantId, Channel.EMAIL);
        registry.tryConsume(tenantId, Channel.EMAIL); // creates new bucket — 2nd load

        verify(rateLimitConfigRepository, times(2))
                .findByTenantIdAndChannel(tenantId, Channel.EMAIL);
    }
}
