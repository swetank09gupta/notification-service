package com.dmg.notification.repository;

import com.dmg.notification.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findByApiKey(String apiKey);
    Optional<Tenant> findByName(String name);
    boolean existsByName(String name);
}
