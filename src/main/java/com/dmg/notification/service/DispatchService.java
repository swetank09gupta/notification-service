package com.dmg.notification.service;

import com.dmg.notification.channel.ChannelDispatcher;
import com.dmg.notification.channel.DispatchResult;
import com.dmg.notification.config.AppProperties;
import com.dmg.notification.domain.DeliveryAttempt;
import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.enums.AttemptStatus;
import com.dmg.notification.domain.enums.NotificationStatus;
import com.dmg.notification.kafka.NotificationEventPublisher;
import com.dmg.notification.kafka.NotificationStatusPublisher;
import com.dmg.notification.ratelimit.RateLimiterRegistry;
import com.dmg.notification.repository.NotificationRequestRepository;
import com.dmg.notification.repository.DeliveryAttemptRepository;
import com.dmg.notification.repository.NotificationRepository;
import com.dmg.notification.observability.NotificationMetrics;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Executes the actual dispatch of a single Notification to its channel.
 *
 * Circuit breaker per channel (Resilience4j):
 * - Opens after 50% failure rate in last 10 calls (min 5 required).
 * - While open, calls fail fast as TRANSIENT failures → scheduled for retry.
 * - Half-open: allows 3 probe calls to test recovery.
 * - This prevents the flash-sale scenario from overwhelming a degraded channel
 *   (e.g., WhatsApp down) with retries — requests queue in DB instead.
 *
 * Called by:
 * - NotificationDispatchConsumer (Kafka consumer thread)
 * - NotificationScheduler.retryPoller (scheduled thread)
 *
 * Never throws — all outcomes are modelled as DB state updates.
 */
@Slf4j
@Service
public class DispatchService {

    private final Map<String, ChannelDispatcher> dispatchers;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final NotificationEventPublisher eventPublisher;
    private final NotificationStatusPublisher statusPublisher;
    private final NotificationRepository notificationRepository;
    private final NotificationRequestRepository requestRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final AppProperties appProperties;
    private final NotificationMetrics metrics;

    public DispatchService(java.util.List<ChannelDispatcher> channelDispatchers,
                           RateLimiterRegistry rateLimiterRegistry,
                           CircuitBreakerRegistry circuitBreakerRegistry,
                           NotificationEventPublisher eventPublisher,
                           NotificationStatusPublisher statusPublisher,
                           NotificationRepository notificationRepository,
                           NotificationRequestRepository requestRepository,
                           DeliveryAttemptRepository deliveryAttemptRepository,
                           AppProperties appProperties,
                           NotificationMetrics metrics) {
        this.dispatchers = new java.util.HashMap<>();
        for (ChannelDispatcher d : channelDispatchers) {
            this.dispatchers.put(d.channel().name(), d);
        }
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.eventPublisher = eventPublisher;
        this.statusPublisher = statusPublisher;
        this.notificationRepository = notificationRepository;
        this.requestRepository = requestRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.appProperties = appProperties;
        this.metrics = metrics;
    }

    @Transactional
    public void executeDispatch(UUID notificationId) {
        // ObjectOptimisticLockingFailureException propagates out of this @Transactional method.
        // The Kafka DefaultErrorHandler catches it, retries the message up to 3 times.
        // On retry the notification status is checked first; if already DELIVERED by another
        // instance it exits cleanly — no double delivery.
        Notification notification;
        try {
            notification = notificationRepository.findById(notificationId)
                    .orElseThrow(() -> new IllegalStateException("Notification not found: " + notificationId));
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Optimistic lock conflict on notification={} — another instance is dispatching it", notificationId);
            throw e; // let DefaultErrorHandler retry; the other instance will finish
        }

        if (notification.getStatus() == NotificationStatus.DELIVERED
                || notification.getStatus() == NotificationStatus.EXHAUSTED) {
            log.debug("Skipping terminal notification {} status={}", notificationId, notification.getStatus());
            return;
        }

        UUID tenantId = notification.getTenant().getId();

        // MDC context — enriches every log line in this dispatch with identifiers
        MDC.put("notificationId", notificationId.toString());
        MDC.put("tenantId", tenantId.toString());
        MDC.put("channel", notification.getChannel().name());
        if (notification.getRequest() != null && notification.getRequest().getCorrelationId() != null) {
            MDC.put("correlationId", notification.getRequest().getCorrelationId());
        }

        // Prerequisite check ─────────────────────────────────────────────────
        // If this notification's parent request declares a dependsOnIdempotencyKey,
        // ensure that prerequisite request is fully delivered before dispatching.
        // Example: ORDER_SHIPPED should not go out before ORDER_PLACED is confirmed.
        //
        // When not met: reschedule 5s from now WITHOUT burning a retry attempt.
        // The DB scheduler will re-publish to Kafka when next_retry_at is due.
        String prereq = notification.getRequest() != null
                ? notification.getRequest().getDependsOnIdempotencyKey()
                : null;
        if (prereq != null && !requestRepository.isFullyDelivered(prereq)) {
            log.debug("Prerequisite '{}' not yet DELIVERED — rescheduling notificationId={} in 5s",
                    prereq, notificationId);
            notification.setStatus(NotificationStatus.RATE_LIMITED);
            notification.setNextRetryAt(Instant.now().plusSeconds(5));
            notificationRepository.save(notification);
            // Publish WAITING status so calling service sees it's pending prerequisite
            statusPublisher.publishStatus(notification, NotificationStatus.RATE_LIMITED,
                    "Waiting for prerequisite: " + prereq);
            metrics.recordPrerequisiteWait(notification.getChannel());
            return;
        }

        // Rate limit check (Redis primary, in-memory fallback)
        if (!rateLimiterRegistry.tryConsume(tenantId, notification.getChannel())) {
            int attempt = notification.getAttemptCount() + 1;
            persistAttempt(notification, attempt, AttemptStatus.RATE_LIMITED, "Rate limit exceeded", null, 0);
            notification.setStatus(NotificationStatus.RATE_LIMITED);
            notification.setAttemptCount(attempt);
            notification.setNextRetryAt(computeNextRetry(attempt));
            notificationRepository.save(notification);
            metrics.recordRateLimited(notification.getChannel());
            return;
        }

        ChannelDispatcher dispatcher = dispatchers.get(notification.getChannel().name());
        if (dispatcher == null) {
            persistAttempt(notification, notification.getAttemptCount() + 1,
                    AttemptStatus.FAILED, "No dispatcher for channel: " + notification.getChannel(), null, 0);
            notification.setStatus(NotificationStatus.EXHAUSTED);
            notificationRepository.save(notification);
            return;
        }

        notification.setStatus(NotificationStatus.PROCESSING);
        notificationRepository.save(notification);

        // Circuit breaker wraps the channel dispatch call.
        // If the circuit is OPEN (channel provider is down), CallNotPermittedException is thrown
        // → treated as transient failure, notification is scheduled for later retry.
        // This prevents hammering a down provider with thousands of requests per second.
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(
                notification.getChannel().name().toLowerCase());

        long start = System.currentTimeMillis();
        DispatchResult result;
        try {
            result = cb.executeSupplier(() -> dispatcher.dispatch(notification));
        } catch (CallNotPermittedException e) {
            log.warn("Circuit OPEN for channel={} — failing fast for notificationId={}",
                    notification.getChannel(), notificationId);
            result = DispatchResult.transientFailure("Circuit breaker open: " + notification.getChannel());
        } catch (Exception e) {
            // Unexpected exception from dispatcher (e.g., the WhatsApp circuit-trip test stub)
            log.error("Unexpected exception from dispatcher channel={} notificationId={}",
                    notification.getChannel(), notificationId, e);
            result = DispatchResult.transientFailure("Dispatcher threw: " + e.getMessage());
        }
        long durationMs = System.currentTimeMillis() - start;

        int attemptNumber = notification.getAttemptCount() + 1;

        if (result.isSuccess()) {
            persistAttempt(notification, attemptNumber, AttemptStatus.SUCCESS,
                    null, result.getChannelResponse(), durationMs);
            notification.setStatus(NotificationStatus.DELIVERED);
            notification.setAttemptCount(attemptNumber);
            notification.setNextRetryAt(null);
            log.info("Delivered notification={} channel={} attempt={} durationMs={}",
                    notificationId, notification.getChannel(), attemptNumber, durationMs);
        } else {
            persistAttempt(notification, attemptNumber, AttemptStatus.FAILED,
                    result.getErrorMessage(), null, durationMs);
            notification.setAttemptCount(attemptNumber);

            boolean canRetry = result.isRetryable()
                    && attemptNumber < notification.getMaxAttempts();
            if (canRetry) {
                notification.setStatus(NotificationStatus.FAILED);
                notification.setNextRetryAt(computeNextRetry(attemptNumber));
                log.warn("Dispatch failed (transient) notification={} channel={} attempt={} nextRetry={}",
                        notificationId, notification.getChannel(), attemptNumber, notification.getNextRetryAt());
            } else {
                notification.setStatus(NotificationStatus.EXHAUSTED);
                notification.setNextRetryAt(null);
                // Publish to DLQ for observability and alerting
                eventPublisher.publishToDlq(tenantId, notificationId,
                        "exhausted after " + attemptNumber + " attempts: " + result.getErrorMessage());
                log.error("Notification permanently failed — published to DLQ. id={} channel={} attempts={}",
                        notificationId, notification.getChannel(), attemptNumber);
            }
        }

        notificationRepository.save(notification);

        // Record metrics for every terminal dispatch outcome
        metrics.recordDispatch(notification.getChannel(), notification.getStatus(), durationMs);
        if (notification.getStatus() == NotificationStatus.EXHAUSTED) {
            metrics.recordDlq(notification.getChannel());
        }

        // Publish delivery outcome to notification.status — partition key = correlationId.
        // Downstream services (Order, Analytics) subscribe here to know per-channel delivery state.
        statusPublisher.publishStatus(notification, notification.getStatus(),
                result.isSuccess() ? null : result.getErrorMessage());
    }

    private void persistAttempt(Notification notification, int attemptNumber,
                                  AttemptStatus status, String error, String response, long durationMs) {
        DeliveryAttempt attempt = DeliveryAttempt.builder()
                .notification(notification)
                .attemptNumber(attemptNumber)
                .status(status)
                .errorMessage(error)
                .channelResponse(response)
                .attemptedAt(Instant.now())
                .durationMs(durationMs)
                .build();
        deliveryAttemptRepository.save(attempt);
    }

    private Instant computeNextRetry(int attemptNumber) {
        long delaySeconds = appProperties.getRetry().getInitialDelaySeconds()
                * (long) Math.pow(appProperties.getRetry().getBackoffMultiplier(), attemptNumber - 1);
        return Instant.now().plusSeconds(delaySeconds);
    }
}
