package com.dmg.notification.repository;

import com.dmg.notification.domain.DeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {
    List<DeliveryAttempt> findAllByNotificationIdOrderByAttemptNumberAsc(UUID notificationId);
}
