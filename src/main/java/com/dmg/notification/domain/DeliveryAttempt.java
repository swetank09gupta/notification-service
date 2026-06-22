package com.dmg.notification.domain;

import com.dmg.notification.domain.enums.AttemptStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "delivery_attempts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false)
    private Notification notification;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttemptStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "channel_response", columnDefinition = "TEXT")
    private String channelResponse;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    @Column(name = "duration_ms")
    private Long durationMs;
}
