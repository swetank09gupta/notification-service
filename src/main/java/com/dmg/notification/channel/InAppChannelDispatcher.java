package com.dmg.notification.channel;

import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.enums.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class InAppChannelDispatcher implements ChannelDispatcher {

    @Override
    public Channel channel() { return Channel.IN_APP; }

    @Override
    public DispatchResult dispatch(Notification notification) {
        log.info("[IN_APP] tenant={} userId={} body={}",
                notification.getTenant().getId(),
                notification.getRecipientAddress(),
                notification.getRenderedBody());
        return DispatchResult.success("IN_APP_OK:notifId=" + notification.getId());
    }
}
