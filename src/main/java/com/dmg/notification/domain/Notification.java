package com.dmg.notification.domain;

import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.domain.enums.NotificationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private NotificationRequest request;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Column(name = "recipient_address", nullable = false)
    private String recipientAddress;

    @Column(name = "rendered_subject")
    private String renderedSubject;

    @Column(name = "rendered_body", nullable = false, columnDefinition = "TEXT")
    private String renderedBody;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    // Optimistic lock — prevents two scheduler instances from double-dispatching
    // the same notification. If two instances load version=N and both try to write,
    // the second write throws ObjectOptimisticLockingFailureException. The Kafka
    // DefaultErrorHandler retries that message; by then status==DELIVERED so it exits early.
    @Version
    @Column(nullable = false)
    private long version = 0;
}
