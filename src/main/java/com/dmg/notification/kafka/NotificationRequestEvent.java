package com.dmg.notification.kafka;

import java.util.List;
import java.util.UUID;

/**
 * Inbound event published by any upstream service (Order, Payment, Shipping, etc.)
 * directly to the {@code notification.requests} Kafka topic.
 *
 * This is the Kafka-native entry point — no HTTP call required.
 * The partition key is {@code correlationId} (e.g. orderId) which ensures all
 * notification events for the same order land on the same partition and are
 * consumed in the order they were produced.
 *
 * Example order lifecycle:
 * <pre>
 *   ORDER_PLACED   → correlationId=order-123, idempotencyKey=order-123-placed
 *   ORDER_SHIPPED  → correlationId=order-123, idempotencyKey=order-123-shipped,
 *                    dependsOnIdempotencyKey=order-123-placed
 *   ORDER_DELIVERED → correlationId=order-123, idempotencyKey=order-123-delivered,
 *                     dependsOnIdempotencyKey=order-123-shipped
 * </pre>
 */
public class NotificationRequestEvent {

    /** Client-generated UUID. Used as idempotency key. */
    private String requestId;

    private UUID tenantId;

    // Recipient fields (at least one channel-specific address required)
    private String recipientRef;
    private String email;
    private String phone;
    private String whatsappNumber;
    private String deviceToken;
    private String userId;

    /** Channel override list. If absent, resolved from template. */
    private List<String> channels;

    // Template resolution (one of templateId or templateName, or inline body)
    private String templateId;
    private String templateName;
    private String subject;
    private String body;

    /** JSON object string: {"name":"Alice","orderId":"ORD-123"} */
    private String variables;

    /** ISO-8601; null = send immediately. */
    private String scheduledAt;

    // ── Event-driven context ─────────────────────────────────────────────────

    /**
     * Business entity ID (e.g. orderId). Used as the Kafka partition key
     * to guarantee per-entity ordering. Also forwarded verbatim to
     * {@code notification.status} events so consumers can correlate.
     */
    private String correlationId;

    /** Business event name (e.g. ORDER_SHIPPED, PAYMENT_FAILED). */
    private String eventType;

    /**
     * Idempotency key of a prerequisite request that must be fully delivered
     * before any channel in this request is dispatched.
     * If the prerequisite is not yet DELIVERED, dispatch is rescheduled
     * without consuming a retry attempt.
     */
    private String dependsOnIdempotencyKey;

    public NotificationRequestEvent() {}

    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { this.requestId = v; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID v) { this.tenantId = v; }

    public String getRecipientRef() { return recipientRef; }
    public void setRecipientRef(String v) { this.recipientRef = v; }

    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }

    public String getPhone() { return phone; }
    public void setPhone(String v) { this.phone = v; }

    public String getWhatsappNumber() { return whatsappNumber; }
    public void setWhatsappNumber(String v) { this.whatsappNumber = v; }

    public String getDeviceToken() { return deviceToken; }
    public void setDeviceToken(String v) { this.deviceToken = v; }

    public String getUserId() { return userId; }
    public void setUserId(String v) { this.userId = v; }

    public List<String> getChannels() { return channels; }
    public void setChannels(List<String> v) { this.channels = v; }

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String v) { this.templateId = v; }

    public String getTemplateName() { return templateName; }
    public void setTemplateName(String v) { this.templateName = v; }

    public String getSubject() { return subject; }
    public void setSubject(String v) { this.subject = v; }

    public String getBody() { return body; }
    public void setBody(String v) { this.body = v; }

    public String getVariables() { return variables; }
    public void setVariables(String v) { this.variables = v; }

    public String getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(String v) { this.scheduledAt = v; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String v) { this.correlationId = v; }

    public String getEventType() { return eventType; }
    public void setEventType(String v) { this.eventType = v; }

    public String getDependsOnIdempotencyKey() { return dependsOnIdempotencyKey; }
    public void setDependsOnIdempotencyKey(String v) { this.dependsOnIdempotencyKey = v; }

    @Override
    public String toString() {
        return "NotificationRequestEvent{requestId=" + requestId
                + ", tenant=" + tenantId + ", event=" + eventType
                + ", correlation=" + correlationId + "}";
    }
}
