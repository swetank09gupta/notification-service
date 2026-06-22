package com.dmg.notification.dto.response;

import com.dmg.notification.domain.RateLimitConfig;
import com.dmg.notification.domain.enums.Channel;

import java.time.Instant;
import java.util.UUID;

public class RateLimitConfigResponse {

    private UUID id;
    private UUID tenantId;
    private Channel channel;
    private int requestsPerMinute;
    private int requestsPerHour;
    private Instant updatedAt;

    public static RateLimitConfigResponse from(RateLimitConfig c) {
        RateLimitConfigResponse r = new RateLimitConfigResponse();
        r.id = c.getId();
        r.tenantId = c.getTenant() != null ? c.getTenant().getId() : null;
        r.channel = c.getChannel();
        r.requestsPerMinute = c.getRequestsPerMinute();
        r.requestsPerHour = c.getRequestsPerHour();
        r.updatedAt = c.getUpdatedAt();
        return r;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public Channel getChannel() { return channel; }
    public int getRequestsPerMinute() { return requestsPerMinute; }
    public int getRequestsPerHour() { return requestsPerHour; }
    public Instant getUpdatedAt() { return updatedAt; }
}
