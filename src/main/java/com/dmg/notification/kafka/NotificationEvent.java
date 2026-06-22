package com.dmg.notification.kafka;

import java.util.UUID;

/**
 * Kafka event payload for the notification dispatch and retry pipelines.
 * Intentionally lean — the consumer loads full state from the DB.
 * Partitioned by tenantId to preserve per-tenant ordering.
 */
public class NotificationEvent {

    public enum Type { DISPATCH, RETRY }

    private UUID notificationId;
    private UUID tenantId;
    private Type eventType;
    private int attemptCount;

    public NotificationEvent() {}

    public NotificationEvent(UUID notificationId, UUID tenantId, Type eventType) {
        this.notificationId = notificationId;
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.attemptCount = 0;
    }

    public NotificationEvent(UUID notificationId, UUID tenantId, Type eventType, int attemptCount) {
        this.notificationId = notificationId;
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.attemptCount = attemptCount;
    }

    public UUID getNotificationId() { return notificationId; }
    public void setNotificationId(UUID v) { this.notificationId = v; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID v) { this.tenantId = v; }

    public Type getEventType() { return eventType; }
    public void setEventType(Type v) { this.eventType = v; }

    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int v) { this.attemptCount = v; }

    @Override
    public String toString() {
        return "NotificationEvent{id=" + notificationId + ", tenant=" + tenantId
                + ", type=" + eventType + ", attempt=" + attemptCount + "}";
    }
}
