package com.dmg.notification.domain;

import com.dmg.notification.domain.enums.RequestStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id")
    private Template template;

    @Column(name = "recipient_ref", nullable = false)
    private String recipientRef;

    // JSON map of template variables: {"name":"Alice","orderId":"123"}
    @Column(columnDefinition = "TEXT")
    private String variables;

    // Comma-separated channels: "EMAIL,SMS"
    @Column(nullable = false)
    private String channels;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestStatus status = RequestStatus.PENDING;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    // Event-driven ingestion context ─────────────────────────────────────────

    /** Business entity ID (e.g. orderId, userId). Partition key on notification.requests. */
    @Column(name = "correlation_id")
    private String correlationId;

    /** Business event name (e.g. ORDER_SHIPPED). Passed through to notification.status. */
    @Column(name = "event_type")
    private String eventType;

    /**
     * Idempotency key of a prerequisite NotificationRequest that must be fully
     * DELIVERED before any channel in this request is dispatched.
     * Example: ORDER_SHIPPED must wait for ORDER_PLACED to be delivered.
     */
    @Column(name = "depends_on_idempotency_key")
    private String dependsOnIdempotencyKey;

    /** HTTP | KAFKA_EVENT — how this request was ingested. */
    @Column(name = "source")
    private String source = "HTTP";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
