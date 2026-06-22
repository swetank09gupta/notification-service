package com.dmg.notification.dto.response;

import com.dmg.notification.domain.ChannelConfig;
import com.dmg.notification.domain.enums.Channel;

import java.time.Instant;
import java.util.UUID;

public class ChannelConfigResponse {

    private UUID id;
    private Channel channel;
    private boolean active;
    private String configJson;
    private Instant updatedAt;

    public static ChannelConfigResponse from(ChannelConfig c) {
        ChannelConfigResponse r = new ChannelConfigResponse();
        r.id = c.getId();
        r.channel = c.getChannel();
        r.active = c.isActive();
        r.configJson = c.getConfigJson();
        r.updatedAt = c.getUpdatedAt();
        return r;
    }

    public UUID getId() { return id; }
    public Channel getChannel() { return channel; }
    public boolean isActive() { return active; }
    public String getConfigJson() { return configJson; }
    public Instant getUpdatedAt() { return updatedAt; }
}
