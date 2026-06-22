package com.dmg.notification.channel;

import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.enums.Channel;

public interface ChannelDispatcher {
    Channel channel();
    DispatchResult dispatch(Notification notification);
}
