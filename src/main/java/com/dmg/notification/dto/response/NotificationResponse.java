package com.dmg.notification.dto.response;

import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.domain.enums.NotificationStatus;

import java.time.Instant;
import java.util.UUID;

public class NotificationResponse {

    private UUID id;
    private UUID requestId;
    private Channel channel;
    private String recipientAddress;
    private String renderedSubject;
    private NotificationStatus status;
    private int attemptCount;
    private int maxAttempts;
    private Instant nextRetryAt;
    private Instant createdAt;

    public static NotificationResponse from(Notification n) {
        NotificationResponse r = new NotificationResponse();
        r.id = n.getId();
        r.requestId = n.getRequest() != null ? n.getRequest().getId() : null;
        r.channel = n.getChannel();
        r.recipientAddress = n.getRecipientAddress();
        r.renderedSubject = n.getRenderedSubject();
        r.status = n.getStatus();
        r.attemptCount = n.getAttemptCount();
        r.maxAttempts = n.getMaxAttempts();
        r.nextRetryAt = n.getNextRetryAt();
        r.createdAt = n.getCreatedAt();
        return r;
    }

    public UUID getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public Channel getChannel() { return channel; }
    public String getRecipientAddress() { return recipientAddress; }
    public String getRenderedSubject() { return renderedSubject; }
    public NotificationStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public Instant getCreatedAt() { return createdAt; }
}
