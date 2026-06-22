package com.dmg.notification.dto.request;

import com.dmg.notification.domain.enums.Channel;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
public class SendNotificationRequest {

    // Recipient
    @Size(max = 255, message = "recipientRef must not exceed 255 characters")
    private String recipientRef;

    @Email(message = "email must be a valid email address")
    @Size(max = 320, message = "email must not exceed 320 characters")
    private String email;

    @Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "phone must be in E.164 format e.g. +919876543210")
    private String phone;

    @Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "whatsappNumber must be in E.164 format e.g. +919876543210")
    private String whatsappNumber;

    @Size(max = 512, message = "deviceToken must not exceed 512 characters")
    private String deviceToken;

    @Size(max = 255, message = "userId must not exceed 255 characters")
    private String userId;

    // Template resolution (one of: templateId, templateName+channel, or inline body)
    private UUID templateId;

    @Size(max = 255, message = "templateName must not exceed 255 characters")
    private String templateName;

    private Channel channel;

    private List<Channel> channels;

    // Inline (no template)
    @Size(max = 500, message = "subject must not exceed 500 characters")
    private String subject;

    @Size(max = 10_000, message = "body must not exceed 10,000 characters")
    private String body;

    // Template variables: JSON object string {"name":"Alice"}
    @Size(max = 10_000, message = "variables JSON must not exceed 10,000 characters")
    private String variables;

    // Scheduling
    private Instant scheduledAt;

    // Idempotency
    @Size(max = 255, message = "idempotencyKey must not exceed 255 characters")
    private String idempotencyKey;

    // Event-driven context (optional; used when request originates from a Kafka event)
    @Size(max = 255, message = "correlationId must not exceed 255 characters")
    private String correlationId;

    @Size(max = 255, message = "eventType must not exceed 255 characters")
    private String eventType;

    @Size(max = 255, message = "dependsOnIdempotencyKey must not exceed 255 characters")
    private String dependsOnIdempotencyKey;

    @Pattern(regexp = "^(HTTP|KAFKA_EVENT)$", message = "source must be HTTP or KAFKA_EVENT")
    private String source = "HTTP";
}
