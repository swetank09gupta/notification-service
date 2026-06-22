package com.dmg.notification.domain;

import com.dmg.notification.domain.enums.Channel;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "templates",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "name", "channel"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Template {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private int version = 1;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
