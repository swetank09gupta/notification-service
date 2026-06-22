package com.dmg.notification.repository;

import com.dmg.notification.domain.Template;
import com.dmg.notification.domain.enums.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateRepository extends JpaRepository<Template, UUID> {
    List<Template> findAllByTenantIdAndActiveTrue(UUID tenantId);
    Optional<Template> findByTenantIdAndNameAndChannelAndActiveTrue(UUID tenantId, String name, Channel channel);
    Optional<Template> findByIdAndTenantId(UUID id, UUID tenantId);
    boolean existsByTenantIdAndNameAndChannel(UUID tenantId, String name, Channel channel);
}
