package com.dmg.notification.scheduler;

import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.NotificationRequest;
import com.dmg.notification.kafka.NotificationEventPublisher;
import com.dmg.notification.repository.NotificationRepository;
import com.dmg.notification.repository.NotificationRequestRepository;
import com.dmg.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Two polling loops:
 *
 * 1. retryPoller — picks up FAILED/RATE_LIMITED notifications whose next_retry_at is now due
 *    and republishes them to the Kafka retry topic.  The consumer handles actual dispatch.
 *    Delay schedule (exponential backoff): 30s → 2m → 8m → 32m → 2h.
 *
 * 2. schedulePoller — picks up SCHEDULED requests that are now past their scheduled_at
 *    and fans out per-channel Notification rows → published to Kafka dispatch topic.
 *
 * External channel down (circuit breaker open):
 * - Failed notifications accumulate in DB with FAILED + next_retry_at.
 * - Scheduler will republish them once next_retry_at is due.
 * - By that time, Resilience4j may have transitioned the circuit to HALF_OPEN,
 *   allowing probe calls. If still open, calls fail fast and another retry is scheduled.
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class NotificationScheduler {

    private final NotificationRepository notificationRepository;
    private final NotificationRequestRepository requestRepository;
    private final NotificationEventPublisher eventPublisher;
    private final NotificationService notificationService;

    // @Transactional is required — FOR UPDATE SKIP LOCKED holds the row lock
    // only within the transaction. Without it, SKIP LOCKED would be a no-op.
    // The lock prevents other scheduler instances from picking the same rows.
    @Transactional
    @Scheduled(fixedDelayString = "${app.scheduler.retry-poll-interval-ms:10000}")
    public void retryPoller() {
        List<Notification> retryable = notificationRepository.findAndLockRetryable(Instant.now());
        if (!retryable.isEmpty()) {
            log.info("RetryPoller claiming {} notifications for retry (SKIP LOCKED)", retryable.size());
        }
        for (Notification n : retryable) {
            eventPublisher.publishRetry(n.getTenant().getId(), n.getId(), n.getAttemptCount());
        }
    }

    @Transactional
    @Scheduled(fixedDelayString = "${app.scheduler.scheduled-poll-interval-ms:10000}")
    public void schedulePoller() {
        List<NotificationRequest> due = requestRepository.findAndLockDueScheduled(Instant.now());
        if (!due.isEmpty()) {
            log.info("SchedulePoller claiming {} due scheduled requests (SKIP LOCKED)", due.size());
        }
        for (NotificationRequest req : due) {
            notificationService.dispatchScheduled(req);
        }
    }
}
