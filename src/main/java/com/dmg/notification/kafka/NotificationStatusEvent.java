package com.dmg.notification.kafka;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound event published to {@code notification.status} after every
 * meaningful status transition of an individual Notification (one channel).
 *
 * Consuming services (Order Service, Analytics, etc.) use this to:
 *  - Know when a customer has been successfully notified
 *  - Trigger fallback workflows when a channel is EXHAUSTED
 *  - Build per-order notification dashboards (filter by correlationId)
 *
 * Partition key = {@code correlationId} (if present) else {@code tenantId}.
 * This ensures all status updates for the same order arrive in order at the consumer.
 *
 * Typical consumer pattern (Order Service):
 * <pre>
 *   if (event.correlationId.equals(orderId)
 *       && "ORDER_SHIPPED".equals(event.eventType)
 *       && "DELIVERED".equals(event.status)) {
 *       orderService.markCustomerNotified(orderId, event.channel);
 *   }
 * </pre>
 */
public class NotificationStatusEvent {

    private UUID notificationId;
    private UUID requestId;
    private UUID tenantId;
    private String channel;

    /**
     * Terminal or in-progress status:
     *   DELIVERED        — successfully sent
     *   FAILED           — transient failure, will retry
     *   EXHAUSTED        — max attempts reached, no more retries
     *   RATE_LIMITED     — temporarily throttled, will retry
     *   WAITING          — prerequisite not yet delivered
     */
    private String status;

    private Instant eventTime;
    private int attemptCount;
    private String errorMessage;

    // ── Pass-through business context ────────────────────────────────────────
    /** e.g. orderId — enables per-order filtering without DB join */
    private String correlationId;
    /** e.g. ORDER_SHIPPED */
    private String eventType;
    /** Idempotency key of the parent NotificationRequest */
    private String idempotencyKey;

    public NotificationStatusEvent() {}

    public static NotificationStatusEvent from(
            UUID notificationId, UUID requestId, UUID tenantId,
            String channel, String status, int attemptCount, String errorMessage,
            String correlationId, String eventType, String idempotencyKey) {
        NotificationStatusEvent e = new NotificationStatusEvent();
        e.notificationId = notificationId;
        e.requestId = requestId;
        e.tenantId = tenantId;
        e.channel = channel;
        e.status = status;
        e.attemptCount = attemptCount;
        e.errorMessage = errorMessage;
        e.correlationId = correlationId;
        e.eventType = eventType;
        e.idempotencyKey = idempotencyKey;
        e.eventTime = Instant.now();
        return e;
    }

    public UUID getNotificationId() { return notificationId; }
    public void setNotificationId(UUID v) { this.notificationId = v; }

    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID v) { this.requestId = v; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID v) { this.tenantId = v; }

    public String getChannel() { return channel; }
    public void setChannel(String v) { this.channel = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public Instant getEventTime() { return eventTime; }
    public void setEventTime(Instant v) { this.eventTime = v; }

    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int v) { this.attemptCount = v; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String v) { this.correlationId = v; }

    public String getEventType() { return eventType; }
    public void setEventType(String v) { this.eventType = v; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String v) { this.idempotencyKey = v; }
}
