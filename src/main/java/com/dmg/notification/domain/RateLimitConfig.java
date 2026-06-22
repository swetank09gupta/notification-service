package com.dmg.notification.domain;

import com.dmg.notification.domain.enums.Channel;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rate_limit_configs",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "channel"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RateLimitConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Column(name = "requests_per_minute", nullable = false)
    private int requestsPerMinute = 60;

    @Column(name = "requests_per_hour", nullable = false)
    private int requestsPerHour = 1000;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
