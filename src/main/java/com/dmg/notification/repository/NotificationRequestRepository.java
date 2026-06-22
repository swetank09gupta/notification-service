package com.dmg.notification.repository;

import com.dmg.notification.domain.NotificationRequest;
import com.dmg.notification.domain.enums.RequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRequestRepository extends JpaRepository<NotificationRequest, UUID> {

    Optional<NotificationRequest> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<NotificationRequest> findByIdempotencyKey(String idempotencyKey);

    Page<NotificationRequest> findAllByTenantId(UUID tenantId, Pageable pageable);

    // FOR UPDATE SKIP LOCKED — prevents multiple scheduler instances from
    // dispatching the same scheduled request simultaneously.
    @Query(value = """
            SELECT * FROM notification_requests
            WHERE status = 'SCHEDULED'
              AND scheduled_at IS NOT NULL
              AND scheduled_at <= :now
            ORDER BY scheduled_at ASC
            LIMIT 50
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<NotificationRequest> findAndLockDueScheduled(@Param("now") Instant now);

    // Count by tenant and status for reports
    long countByTenantIdAndStatus(UUID tenantId, RequestStatus status);

    /**
     * Dependency check: returns true if ALL Notification rows for the request
     * identified by idempotencyKey are in DELIVERED status.
     * Used to gate ORDER_SHIPPED before ORDER_PLACED is confirmed delivered.
     */
    @Query("""
        SELECT CASE WHEN COUNT(n) = 0 THEN false
                    WHEN COUNT(n) = SUM(CASE WHEN n.status = 'DELIVERED' THEN 1 ELSE 0 END) THEN true
                    ELSE false END
        FROM NotificationRequest r
        JOIN Notification n ON n.request = r
        WHERE r.idempotencyKey = :idempotencyKey
        """)
    boolean isFullyDelivered(String idempotencyKey);

    /** Find all requests for a correlation ID (e.g. all notifications for one order). */
    List<NotificationRequest> findByTenantIdAndCorrelationIdOrderByCreatedAtAsc(UUID tenantId, String correlationId);
}
