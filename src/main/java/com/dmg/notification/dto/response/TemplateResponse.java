package com.dmg.notification.dto.response;

import com.dmg.notification.domain.Template;
import com.dmg.notification.domain.enums.Channel;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class TemplateResponse {
    private UUID id;
    private UUID tenantId;
    private String name;
    private Channel channel;
    private String subject;
    private String body;
    private boolean active;
    private int version;
    private Instant createdAt;
    private Instant updatedAt;

    public static TemplateResponse from(Template t) {
        return TemplateResponse.builder()
                .id(t.getId())
                .tenantId(t.getTenant().getId())
                .name(t.getName())
                .channel(t.getChannel())
                .subject(t.getSubject())
                .body(t.getBody())
                .active(t.isActive())
                .version(t.getVersion())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }
}
