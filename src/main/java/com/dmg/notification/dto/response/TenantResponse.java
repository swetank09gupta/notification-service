package com.dmg.notification.dto.response;

import com.dmg.notification.domain.Tenant;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class TenantResponse {
    private UUID id;
    private String name;
    private String apiKey;
    private boolean active;
    private Instant createdAt;

    public static TenantResponse from(Tenant t) {
        return TenantResponse.builder()
                .id(t.getId())
                .name(t.getName())
                .apiKey(t.getApiKey())
                .active(t.isActive())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
