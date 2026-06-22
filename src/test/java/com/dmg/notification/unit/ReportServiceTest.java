package com.dmg.notification.unit;

import com.dmg.notification.domain.DeliveryAttempt;
import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.Tenant;
import com.dmg.notification.domain.enums.AttemptStatus;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.domain.enums.NotificationStatus;
import com.dmg.notification.domain.enums.RequestStatus;
import com.dmg.notification.dto.response.DeliveryReportResponse;
import com.dmg.notification.repository.DeliveryAttemptRepository;
import com.dmg.notification.repository.NotificationRepository;
import com.dmg.notification.repository.NotificationRequestRepository;
import com.dmg.notification.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock NotificationRequestRepository requestRepository;
    @Mock NotificationRepository notificationRepository;
    @Mock DeliveryAttemptRepository deliveryAttemptRepository;

    ReportService service;
    UUID tenantId;

    @BeforeEach
    void setUp() {
        service = new ReportService(requestRepository, notificationRepository, deliveryAttemptRepository);
        tenantId = UUID.randomUUID();
    }

    @Test
    void getReport_aggregatesCountsCorrectly() {
        // Request counts
        when(requestRepository.countByTenantIdAndStatus(tenantId, RequestStatus.COMPLETED)).thenReturn(10L);
        when(requestRepository.countByTenantIdAndStatus(tenantId, RequestStatus.PROCESSING)).thenReturn(2L);
        when(requestRepository.countByTenantIdAndStatus(tenantId, RequestStatus.FAILED)).thenReturn(1L);
        when(requestRepository.countByTenantIdAndStatus(tenantId, RequestStatus.PARTIALLY_FAILED)).thenReturn(0L);

        // Notification status counts
        when(notificationRepository.countByTenantIdAndStatus(tenantId, NotificationStatus.DELIVERED)).thenReturn(10L);
        when(notificationRepository.countByTenantIdAndStatus(tenantId, NotificationStatus.EXHAUSTED)).thenReturn(1L);
        when(notificationRepository.countByTenantIdAndStatus(tenantId, NotificationStatus.PENDING)).thenReturn(2L);
        when(notificationRepository.countByTenantIdAndStatus(tenantId, NotificationStatus.FAILED)).thenReturn(0L);

        // Per-channel breakdowns — EMAIL only has data
        when(notificationRepository.countByTenantIdAndChannelAndStatus(
                tenantId, Channel.EMAIL, NotificationStatus.DELIVERED)).thenReturn(8L);
        when(notificationRepository.countByTenantIdAndChannelAndStatus(
                tenantId, Channel.EMAIL, NotificationStatus.EXHAUSTED)).thenReturn(1L);
        when(notificationRepository.countByTenantIdAndChannelAndStatus(
                tenantId, Channel.EMAIL, NotificationStatus.FAILED)).thenReturn(0L);
        when(notificationRepository.countByTenantIdAndChannelAndStatus(
                tenantId, Channel.EMAIL, NotificationStatus.RATE_LIMITED)).thenReturn(0L);

        // All other channels return 0
        when(notificationRepository.countByTenantIdAndChannelAndStatus(
                eq(tenantId), argThat(c -> c != Channel.EMAIL), any())).thenReturn(0L);

        DeliveryReportResponse report = service.getReport(tenantId);

        assertThat(report.getTotalRequests()).isEqualTo(13L); // 10+2+1+0
        assertThat(report.getDelivered()).isEqualTo(10L);
        assertThat(report.getFailed()).isEqualTo(1L);
        assertThat(report.getPendingOrRetrying()).isEqualTo(2L);
        assertThat(report.getEmailStats().getDelivered()).isEqualTo(8L);
        assertThat(report.getEmailStats().getFailed()).isEqualTo(1L);
        assertThat(report.getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void getAttempts_returnsOrderedListFromRepository() {
        UUID notificationId = UUID.randomUUID();
        Tenant tenant = Tenant.builder().id(tenantId).name("T").apiKey("k").active(true).build();
        Notification notif = Notification.builder()
                .id(notificationId).tenant(tenant).channel(Channel.EMAIL)
                .recipientAddress("a@b.com").renderedBody("hi")
                .status(NotificationStatus.DELIVERED).maxAttempts(5).build();

        List<DeliveryAttempt> attempts = List.of(
                DeliveryAttempt.builder().id(UUID.randomUUID()).notification(notif)
                        .attemptNumber(1).status(AttemptStatus.FAILED)
                        .errorMessage("timeout").attemptedAt(Instant.now()).durationMs(100L).build(),
                DeliveryAttempt.builder().id(UUID.randomUUID()).notification(notif)
                        .attemptNumber(2).status(AttemptStatus.SUCCESS)
                        .channelResponse("OK").attemptedAt(Instant.now()).durationMs(50L).build()
        );

        when(deliveryAttemptRepository.findAllByNotificationIdOrderByAttemptNumberAsc(notificationId))
                .thenReturn(attempts);

        List<DeliveryAttempt> result = service.getAttempts(notificationId);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getAttemptNumber()).isEqualTo(1);
        assertThat(result.get(1).getStatus()).isEqualTo(AttemptStatus.SUCCESS);
    }
}
