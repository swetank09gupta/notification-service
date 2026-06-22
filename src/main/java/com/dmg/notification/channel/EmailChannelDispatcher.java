package com.dmg.notification.channel;

import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.enums.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class EmailChannelDispatcher implements ChannelDispatcher {

    @Override
    public Channel channel() { return Channel.EMAIL; }

    @Override
    public DispatchResult dispatch(Notification notification) {
        log.info("[EMAIL] tenant={} to={} subject={} body={}",
                notification.getTenant().getId(),
                notification.getRecipientAddress(),
                notification.getRenderedSubject(),
                notification.getRenderedBody());
        // Stub: simulate occasional transient failure for testing retry logic
        if (notification.getRecipientAddress().startsWith("fail-transient@")) {
            return DispatchResult.transientFailure("SMTP connection timeout (simulated)");
        }
        if (notification.getRecipientAddress().startsWith("fail-permanent@")) {
            return DispatchResult.permanentFailure("Invalid email address (simulated)");
        }
        return DispatchResult.success("EMAIL_OK:msgId=" + notification.getId());
    }
}
