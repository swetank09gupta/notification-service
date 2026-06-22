package com.dmg.notification.channel;

import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.enums.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SmsChannelDispatcher implements ChannelDispatcher {

    @Override
    public Channel channel() { return Channel.SMS; }

    @Override
    public DispatchResult dispatch(Notification notification) {
        log.info("[SMS] tenant={} to={} body={}",
                notification.getTenant().getId(),
                notification.getRecipientAddress(),
                notification.getRenderedBody());
        if (notification.getRecipientAddress().startsWith("fail-transient-")) {
            return DispatchResult.transientFailure("Gateway timeout (simulated)");
        }
        return DispatchResult.success("SMS_OK:sid=" + notification.getId());
    }
}
