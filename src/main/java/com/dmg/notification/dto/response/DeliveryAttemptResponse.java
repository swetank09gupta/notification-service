package com.dmg.notification.dto.response;

import com.dmg.notification.domain.DeliveryAttempt;
import com.dmg.notification.domain.enums.AttemptStatus;

import java.time.Instant;
import java.util.UUID;

public class DeliveryAttemptResponse {

    private UUID id;
    private UUID notificationId;
    private int attemptNumber;
    private AttemptStatus status;
    private String errorMessage;
    private String channelResponse;
    private Instant attemptedAt;
    private Long durationMs;

    public static DeliveryAttemptResponse from(DeliveryAttempt a) {
        DeliveryAttemptResponse r = new DeliveryAttemptResponse();
        r.id = a.getId();
        r.notificationId = a.getNotification() != null ? a.getNotification().getId() : null;
        r.attemptNumber = a.getAttemptNumber();
        r.status = a.getStatus();
        r.errorMessage = a.getErrorMessage();
        r.channelResponse = a.getChannelResponse();
        r.attemptedAt = a.getAttemptedAt();
        r.durationMs = a.getDurationMs();
        return r;
    }

    public UUID getId() { return id; }
    public UUID getNotificationId() { return notificationId; }
    public int getAttemptNumber() { return attemptNumber; }
    public AttemptStatus getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public String getChannelResponse() { return channelResponse; }
    public Instant getAttemptedAt() { return attemptedAt; }
    public Long getDurationMs() { return durationMs; }
}
