package com.dmg.notification.repository;

import com.dmg.notification.domain.RateLimitConfig;
import com.dmg.notification.domain.enums.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RateLimitConfigRepository extends JpaRepository<RateLimitConfig, UUID> {
    List<RateLimitConfig> findAllByTenantId(UUID tenantId);
    Optional<RateLimitConfig> findByTenantIdAndChannel(UUID tenantId, Channel channel);
}
