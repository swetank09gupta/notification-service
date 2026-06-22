package com.dmg.notification.repository;

import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.domain.enums.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findAllByRequestId(UUID requestId);

    // FOR UPDATE SKIP LOCKED — each scheduler instance claims its own batch of rows.
    // Without this, every instance would publish retry events for the same notifications
    // leading to duplicate dispatches across the cluster.
    //
    // Also fixes: RATE_LIMITED was excluded before — those notifications were silently
    // dropped and never retried. Now both FAILED and RATE_LIMITED are picked up.
    @Query(value = """
            SELECT * FROM notifications
            WHERE status IN ('FAILED', 'RATE_LIMITED')
              AND attempt_count < max_attempts
              AND next_retry_at IS NOT NULL
              AND next_retry_at <= :now
            ORDER BY next_retry_at ASC
            LIMIT 100
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Notification> findAndLockRetryable(@Param("now") Instant now);

    Page<Notification> findAllByTenantId(UUID tenantId, Pageable pageable);

    // Delivery report aggregations
    long countByTenantIdAndStatus(UUID tenantId, NotificationStatus status);

    long countByTenantIdAndChannelAndStatus(UUID tenantId, Channel channel, NotificationStatus status);
}
