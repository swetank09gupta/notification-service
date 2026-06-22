package com.dmg.notification.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data @Builder
public class DeliveryReportResponse {
    private UUID tenantId;
    private long totalRequests;
    private long delivered;
    private long failed;
    private long pendingOrRetrying;
    private ChannelBreakdown emailStats;
    private ChannelBreakdown smsStats;
    private ChannelBreakdown pushStats;
    private ChannelBreakdown inAppStats;

    @Data @AllArgsConstructor
    public static class ChannelBreakdown {
        private long delivered;
        private long failed;
        private long retrying;
    }
}
