package com.dmg.notification.domain;

import com.dmg.notification.domain.enums.Channel;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "channel_configs",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "channel"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChannelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Column(nullable = false)
    private boolean active = true;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
