package com.dmg.notification.service;

import com.dmg.notification.domain.*;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.domain.enums.NotificationStatus;
import com.dmg.notification.domain.enums.RequestStatus;
import com.dmg.notification.dto.request.SendNotificationRequest;
import com.dmg.notification.dto.response.NotificationRequestResponse;
import com.dmg.notification.exception.DuplicateRequestException;
import com.dmg.notification.exception.TenantNotFoundException;
import com.dmg.notification.exception.TemplateNotFoundException;
import com.dmg.notification.kafka.NotificationEventPublisher;
import com.dmg.notification.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final TenantRepository tenantRepository;
    private final TemplateRepository templateRepository;
    private final NotificationRequestRepository requestRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationEventPublisher eventPublisher;
    private final TemplateEngine templateEngine;
    private final ObjectMapper objectMapper;

    @Transactional
    public NotificationRequestResponse send(UUID tenantId, SendNotificationRequest req) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .filter(Tenant::isActive)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));

        // Idempotency check
        if (StringUtils.hasText(req.getIdempotencyKey())) {
            Optional<NotificationRequest> existing = requestRepository.findByIdempotencyKey(req.getIdempotencyKey());
            if (existing.isPresent()) {
                log.debug("Duplicate request idempotencyKey={}", req.getIdempotencyKey());
                throw new DuplicateRequestException(req.getIdempotencyKey(), existing.get().getId());
            }
        }

        Template template = resolveTemplate(tenantId, req);
        Map<String, String> variables = parseVariables(req.getVariables());
        List<Channel> channels = resolveChannels(req, template);

        boolean isScheduled = req.getScheduledAt() != null && req.getScheduledAt().isAfter(Instant.now());

        NotificationRequest notifRequest = NotificationRequest.builder()
                .tenant(tenant)
                .template(template)
                .recipientRef(req.getRecipientRef())
                .variables(req.getVariables())
                .channels(String.join(",", channels.stream().map(Enum::name).toList()))
                .status(isScheduled ? RequestStatus.SCHEDULED : RequestStatus.PENDING)
                .scheduledAt(req.getScheduledAt())
                .idempotencyKey(req.getIdempotencyKey())
                .correlationId(req.getCorrelationId())
                .eventType(req.getEventType())
                .dependsOnIdempotencyKey(req.getDependsOnIdempotencyKey())
                .source(req.getSource() != null ? req.getSource() : "HTTP")
                .build();
        notifRequest = requestRepository.save(notifRequest);

        if (!isScheduled) {
            List<Notification> notifications = createNotifications(notifRequest, tenant, template, channels, variables, req);
            notifRequest.setStatus(RequestStatus.PROCESSING);
            requestRepository.save(notifRequest);

            // Publish to Kafka — returns immediately (202), consumer dispatches asynchronously.
            // Under flash-sale burst load this prevents back-pressure on the HTTP tier;
            // the consumer group absorbs the burst via its concurrency setting.
            for (Notification n : notifications) {
                eventPublisher.publishDispatch(tenantId, n.getId());
            }
        }

        log.info("NotificationRequest created id={} scheduled={} channels={}", notifRequest.getId(), isScheduled, notifRequest.getChannels());
        return NotificationRequestResponse.from(notifRequest);
    }

    /** Called by the scheduler for due scheduled requests. */
    @Transactional
    public void dispatchScheduled(NotificationRequest request) {
        Tenant tenant = request.getTenant();
        Template template = request.getTemplate();
        Map<String, String> variables = parseVariables(request.getVariables());
        List<Channel> channels = Arrays.stream(request.getChannels().split(","))
                .map(Channel::valueOf)
                .toList();

        List<Notification> notifications = createNotifications(request, tenant, template, channels, variables, null);
        request.setStatus(RequestStatus.PROCESSING);
        requestRepository.save(request);

        for (Notification n : notifications) {
            eventPublisher.publishDispatch(tenant.getId(), n.getId());
        }
    }

    @Transactional(readOnly = true)
    public NotificationRequestResponse getRequest(UUID tenantId, UUID requestId) {
        NotificationRequest req = requestRepository.findByIdAndTenantId(requestId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Request not found: " + requestId));
        return NotificationRequestResponse.from(req);
    }

    @Transactional(readOnly = true)
    public Page<NotificationRequestResponse> listRequests(UUID tenantId, Pageable pageable) {
        return requestRepository.findAllByTenantId(tenantId, pageable)
                .map(NotificationRequestResponse::from);
    }

    /** Called by DlqEventConsumer to ensure exhausted notifications are marked terminal. */
    @Transactional
    public void forceExhausted(UUID notificationId) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            if (n.getStatus() != com.dmg.notification.domain.enums.NotificationStatus.DELIVERED) {
                n.setStatus(com.dmg.notification.domain.enums.NotificationStatus.EXHAUSTED);
                n.setNextRetryAt(null);
                notificationRepository.save(n);
            }
        });
    }

    private List<Notification> createNotifications(NotificationRequest request, Tenant tenant,
                                                     Template template, List<Channel> channels,
                                                     Map<String, String> variables,
                                                     SendNotificationRequest req) {
        List<Notification> result = new ArrayList<>();
        for (Channel channel : channels) {
            String address = resolveAddress(channel, req, request.getRecipientRef());
            String renderedSubject = template != null
                    ? templateEngine.render(template.getSubject(), variables)
                    : (req != null ? req.getSubject() : null);
            String rawBody = template != null ? template.getBody()
                    : (req != null ? req.getBody() : request.getRecipientRef());
            String renderedBody = templateEngine.render(rawBody, variables);

            Notification n = Notification.builder()
                    .request(request)
                    .tenant(tenant)
                    .channel(channel)
                    .recipientAddress(address)
                    .renderedSubject(renderedSubject)
                    .renderedBody(renderedBody != null ? renderedBody : "")
                    .status(NotificationStatus.PENDING)
                    .maxAttempts(5)
                    .build();
            result.add(notificationRepository.save(n));
        }
        return result;
    }

    private Template resolveTemplate(UUID tenantId, SendNotificationRequest req) {
        if (req.getTemplateId() != null) {
            return templateRepository.findByIdAndTenantId(req.getTemplateId(), tenantId)
                    .orElseThrow(() -> new TemplateNotFoundException(req.getTemplateId()));
        }
        if (StringUtils.hasText(req.getTemplateName()) && req.getChannel() != null) {
            return templateRepository.findByTenantIdAndNameAndChannelAndActiveTrue(
                    tenantId, req.getTemplateName(), req.getChannel()).orElse(null);
        }
        return null;
    }

    private List<Channel> resolveChannels(SendNotificationRequest req, Template template) {
        if (req.getChannels() != null && !req.getChannels().isEmpty()) {
            return req.getChannels();
        }
        if (template != null) {
            return List.of(template.getChannel());
        }
        if (req.getChannel() != null) {
            return List.of(req.getChannel());
        }
        throw new IllegalArgumentException("At least one channel must be specified");
    }

    private String resolveAddress(Channel channel, SendNotificationRequest req, String recipientRef) {
        if (req == null) return recipientRef;
        return switch (channel) {
            case EMAIL -> req.getEmail() != null ? req.getEmail() : recipientRef;
            case SMS -> req.getPhone() != null ? req.getPhone() : recipientRef;
            case WHATSAPP -> req.getWhatsappNumber() != null ? req.getWhatsappNumber() : req.getPhone() != null ? req.getPhone() : recipientRef;
            case PUSH -> req.getDeviceToken() != null ? req.getDeviceToken() : recipientRef;
            case IN_APP -> req.getUserId() != null ? req.getUserId() : recipientRef;
        };
    }

    private Map<String, String> parseVariables(String json) {
        if (!StringUtils.hasText(json)) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse variables JSON: {}", e.getMessage());
            return Map.of();
        }
    }
}
