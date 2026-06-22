package com.dmg.notification.service;

import com.dmg.notification.domain.DeliveryAttempt;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.domain.enums.NotificationStatus;
import com.dmg.notification.domain.enums.RequestStatus;
import com.dmg.notification.dto.response.DeliveryReportResponse;
import com.dmg.notification.repository.DeliveryAttemptRepository;
import com.dmg.notification.repository.NotificationRepository;
import com.dmg.notification.repository.NotificationRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final NotificationRequestRepository requestRepository;
    private final NotificationRepository notificationRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;

    @Transactional(readOnly = true)
    public DeliveryReportResponse getReport(UUID tenantId) {
        long totalRequests = requestRepository.countByTenantIdAndStatus(tenantId, RequestStatus.COMPLETED)
                + requestRepository.countByTenantIdAndStatus(tenantId, RequestStatus.PROCESSING)
                + requestRepository.countByTenantIdAndStatus(tenantId, RequestStatus.FAILED)
                + requestRepository.countByTenantIdAndStatus(tenantId, RequestStatus.PARTIALLY_FAILED);

        long delivered = notificationRepository.countByTenantIdAndStatus(tenantId, NotificationStatus.DELIVERED);
        long failed = notificationRepository.countByTenantIdAndStatus(tenantId, NotificationStatus.EXHAUSTED);
        long pending = notificationRepository.countByTenantIdAndStatus(tenantId, NotificationStatus.PENDING)
                + notificationRepository.countByTenantIdAndStatus(tenantId, NotificationStatus.FAILED);

        DeliveryReportResponse.ChannelBreakdown emailStats = channelBreakdown(tenantId, Channel.EMAIL);
        DeliveryReportResponse.ChannelBreakdown smsStats = channelBreakdown(tenantId, Channel.SMS);
        DeliveryReportResponse.ChannelBreakdown pushStats = channelBreakdown(tenantId, Channel.PUSH);
        DeliveryReportResponse.ChannelBreakdown inAppStats = channelBreakdown(tenantId, Channel.IN_APP);

        return DeliveryReportResponse.builder()
                .tenantId(tenantId)
                .totalRequests(totalRequests)
                .delivered(delivered)
                .failed(failed)
                .pendingOrRetrying(pending)
                .emailStats(emailStats)
                .smsStats(smsStats)
                .pushStats(pushStats)
                .inAppStats(inAppStats)
                .build();
    }

    @Transactional(readOnly = true)
    public List<DeliveryAttempt> getAttempts(UUID notificationId) {
        return deliveryAttemptRepository.findAllByNotificationIdOrderByAttemptNumberAsc(notificationId);
    }

    private DeliveryReportResponse.ChannelBreakdown channelBreakdown(UUID tenantId, Channel channel) {
        return new DeliveryReportResponse.ChannelBreakdown(
                notificationRepository.countByTenantIdAndChannelAndStatus(tenantId, channel, NotificationStatus.DELIVERED),
                notificationRepository.countByTenantIdAndChannelAndStatus(tenantId, channel, NotificationStatus.EXHAUSTED),
                notificationRepository.countByTenantIdAndChannelAndStatus(tenantId, channel, NotificationStatus.FAILED)
                        + notificationRepository.countByTenantIdAndChannelAndStatus(tenantId, channel, NotificationStatus.RATE_LIMITED)
        );
    }
}
