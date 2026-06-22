package com.dmg.notification.channel;

import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.enums.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * WhatsApp channel dispatcher (stub).
 *
 * Test hooks via recipient address prefix:
 *   fail-transient-wa:... → simulates API timeout (retryable)
 *   fail-permanent-wa:... → simulates invalid number (not retryable)
 *   circuit-trip-wa:...   → throws RuntimeException to trip the circuit breaker
 */
@Slf4j
@Component
public class WhatsAppChannelDispatcher implements ChannelDispatcher {

    @Override
    public Channel channel() {
        return Channel.WHATSAPP;
    }

    @Override
    public DispatchResult dispatch(Notification notification) {
        String to = notification.getRecipientAddress();
        log.info("[WhatsApp] Sending to={} notificationId={}", to, notification.getId());

        if (to != null && to.startsWith("fail-transient-wa:")) {
            log.warn("[WhatsApp] Simulating transient failure for {}", to);
            return DispatchResult.transientFailure("WhatsApp API request timed out (simulated)");
        }
        if (to != null && to.startsWith("fail-permanent-wa:")) {
            log.warn("[WhatsApp] Simulating permanent failure for {}", to);
            return DispatchResult.permanentFailure("WhatsApp number not registered (simulated)");
        }
        if (to != null && to.startsWith("circuit-trip-wa:")) {
            // Throw to increment Resilience4j failure counter and trip the circuit
            throw new RuntimeException("WhatsApp service unavailable (simulated outage)");
        }

        String messageId = "wa-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[WhatsApp] Delivered messageId={} to={}", messageId, to);
        return DispatchResult.success(messageId);
    }
}
