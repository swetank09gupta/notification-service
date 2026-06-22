package com.dmg.notification.channel;

import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.enums.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PushChannelDispatcher implements ChannelDispatcher {

    @Override
    public Channel channel() { return Channel.PUSH; }

    @Override
    public DispatchResult dispatch(Notification notification) {
        log.info("[PUSH] tenant={} deviceToken={} title={} body={}",
                notification.getTenant().getId(),
                notification.getRecipientAddress(),
                notification.getRenderedSubject(),
                notification.getRenderedBody());
        return DispatchResult.success("PUSH_OK:messageId=" + notification.getId());
    }
}
