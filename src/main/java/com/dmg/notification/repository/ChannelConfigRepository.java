package com.dmg.notification.repository;

import com.dmg.notification.domain.ChannelConfig;
import com.dmg.notification.domain.enums.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChannelConfigRepository extends JpaRepository<ChannelConfig, UUID> {
    List<ChannelConfig> findAllByTenantIdAndActiveTrue(UUID tenantId);
    Optional<ChannelConfig> findByTenantIdAndChannel(UUID tenantId, Channel channel);
}
