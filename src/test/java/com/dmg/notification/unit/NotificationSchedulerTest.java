package com.dmg.notification.unit;

import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.NotificationRequest;
import com.dmg.notification.domain.Tenant;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.domain.enums.NotificationStatus;
import com.dmg.notification.domain.enums.RequestStatus;
import com.dmg.notification.kafka.NotificationEventPublisher;
import com.dmg.notification.repository.NotificationRepository;
import com.dmg.notification.repository.NotificationRequestRepository;
import com.dmg.notification.scheduler.NotificationScheduler;
import com.dmg.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationSchedulerTest {

    @Mock NotificationRepository notificationRepository;
    @Mock NotificationRequestRepository requestRepository;
    @Mock NotificationEventPublisher eventPublisher;
    @Mock NotificationService notificationService;

    NotificationScheduler scheduler;

    UUID tenantId;
    Tenant tenant;

    @BeforeEach
    void setUp() {
        scheduler = new NotificationScheduler(
                notificationRepository, requestRepository, eventPublisher, notificationService);
        tenantId = UUID.randomUUID();
        tenant = Tenant.builder().id(tenantId).name("Co").apiKey("k").active(true).build();
    }

    @Test
    void retryPoller_publishesRetryEventsForRetryableNotifications() {
        UUID n1 = UUID.randomUUID();
        UUID n2 = UUID.randomUUID();
        Notification notif1 = Notification.builder()
                .id(n1).tenant(tenant).channel(Channel.EMAIL)
                .status(NotificationStatus.FAILED).attemptCount(1).maxAttempts(5)
                .recipientAddress("a@b.com").renderedBody("x").build();
        Notification notif2 = Notification.builder()
                .id(n2).tenant(tenant).channel(Channel.SMS)
                .status(NotificationStatus.RATE_LIMITED).attemptCount(2).maxAttempts(5)
                .recipientAddress("+1234").renderedBody("y").build();

        when(notificationRepository.findAndLockRetryable(any(Instant.class)))
                .thenReturn(List.of(notif1, notif2));

        scheduler.retryPoller();

        verify(eventPublisher).publishRetry(tenantId, n1, 1);
        verify(eventPublisher).publishRetry(tenantId, n2, 2);
    }

    @Test
    void retryPoller_withNoRetryable_doesNotPublishAnyEvent() {
        when(notificationRepository.findAndLockRetryable(any(Instant.class)))
                .thenReturn(List.of());

        scheduler.retryPoller();

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void schedulePoller_dispatchesDueScheduledRequests() {
        NotificationRequest req1 = NotificationRequest.builder()
                .id(UUID.randomUUID()).tenant(tenant)
                .channels("EMAIL").status(RequestStatus.SCHEDULED)
                .scheduledAt(Instant.now().minusSeconds(1)).build();
        NotificationRequest req2 = NotificationRequest.builder()
                .id(UUID.randomUUID()).tenant(tenant)
                .channels("SMS").status(RequestStatus.SCHEDULED)
                .scheduledAt(Instant.now().minusSeconds(5)).build();

        when(requestRepository.findAndLockDueScheduled(any(Instant.class)))
                .thenReturn(List.of(req1, req2));

        scheduler.schedulePoller();

        verify(notificationService).dispatchScheduled(req1);
        verify(notificationService).dispatchScheduled(req2);
    }

    @Test
    void schedulePoller_withNothingDue_doesNotDispatch() {
        when(requestRepository.findAndLockDueScheduled(any(Instant.class)))
                .thenReturn(List.of());

        scheduler.schedulePoller();

        verifyNoInteractions(notificationService);
    }
}
