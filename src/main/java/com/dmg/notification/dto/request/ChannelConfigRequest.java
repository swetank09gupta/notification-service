package com.dmg.notification.dto.request;

import com.dmg.notification.domain.enums.Channel;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class ChannelConfigRequest {

    @NotNull
    private Channel channel;

    private boolean active = true;

    @Size(max = 10_000, message = "configJson must not exceed 10,000 characters")
    private String configJson;

    public Channel getChannel() { return channel; }
    public void setChannel(Channel channel) { this.channel = channel; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
}
