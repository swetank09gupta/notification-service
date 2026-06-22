package com.dmg.notification.dto.response;

import com.dmg.notification.domain.NotificationRequest;
import com.dmg.notification.domain.enums.RequestStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data @Builder
public class NotificationRequestResponse {
    private UUID id;
    private UUID tenantId;
    private UUID templateId;
    private String recipientRef;
    private String channels;
    private RequestStatus status;
    private Instant scheduledAt;
    private String idempotencyKey;
    private Instant createdAt;

    public static NotificationRequestResponse from(NotificationRequest r) {
        return NotificationRequestResponse.builder()
                .id(r.getId())
                .tenantId(r.getTenant().getId())
                .templateId(r.getTemplate() != null ? r.getTemplate().getId() : null)
                .recipientRef(r.getRecipientRef())
                .channels(r.getChannels())
                .status(r.getStatus())
                .scheduledAt(r.getScheduledAt())
                .idempotencyKey(r.getIdempotencyKey())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
