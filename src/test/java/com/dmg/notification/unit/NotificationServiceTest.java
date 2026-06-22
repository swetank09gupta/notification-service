package com.dmg.notification.unit;

import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.NotificationRequest;
import com.dmg.notification.domain.Tenant;
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
import com.dmg.notification.service.NotificationService;
import com.dmg.notification.service.TemplateEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    @Mock TenantRepository tenantRepository;
    @Mock TemplateRepository templateRepository;
    @Mock NotificationRequestRepository requestRepository;
    @Mock NotificationRepository notificationRepository;
    @Mock NotificationEventPublisher eventPublisher;

    TemplateEngine templateEngine = new TemplateEngine();
    ObjectMapper objectMapper = new ObjectMapper();
    NotificationService service;

    UUID tenantId;
    Tenant tenant;

    @BeforeEach
    void setUp() {
        service = new NotificationService(
                tenantRepository, templateRepository, requestRepository,
                notificationRepository, eventPublisher, templateEngine, objectMapper);
        tenantId = UUID.randomUUID();
        tenant = Tenant.builder().id(tenantId).name("Acme").apiKey("k").active(true).build();
    }

    @Test
    void send_immediateEmail_createsRequestAndPublishesDispatch() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(requestRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        NotificationRequest saved = buildRequest(RequestStatus.PROCESSING);
        when(requestRepository.save(any())).thenReturn(saved);
        Notification savedNotif = buildNotification();
        when(notificationRepository.save(any())).thenReturn(savedNotif);

        SendNotificationRequest req = new SendNotificationRequest();
        req.setRecipientRef("user1");
        req.setEmail("user1@example.com");
        req.setChannel(Channel.EMAIL);
        req.setBody("Hello!");
        req.setIdempotencyKey("idem-1");

        NotificationRequestResponse resp = service.send(tenantId, req);

        verify(eventPublisher).publishDispatch(eq(tenantId), any(UUID.class));
        assertThat(resp).isNotNull();
    }

    @Test
    void send_inactiveTenant_throwsTenantNotFoundException() {
        tenant.setActive(false);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        assertThatThrownBy(() -> service.send(tenantId, new SendNotificationRequest()))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void send_missingTenant_throwsTenantNotFoundException() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.send(tenantId, new SendNotificationRequest()))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void send_duplicateIdempotencyKey_throwsDuplicateRequestException() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        NotificationRequest existing = buildRequest(RequestStatus.PROCESSING);
        when(requestRepository.findByIdempotencyKey("dup-key")).thenReturn(Optional.of(existing));

        SendNotificationRequest req = new SendNotificationRequest();
        req.setIdempotencyKey("dup-key");
        req.setBody("hi");

        assertThatThrownBy(() -> service.send(tenantId, req))
                .isInstanceOf(DuplicateRequestException.class);
    }

    @Test
    void send_withTemplateIdNotFound_throwsTemplateNotFoundException() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(requestRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        UUID templateId = UUID.randomUUID();
        when(templateRepository.findByIdAndTenantId(templateId, tenantId)).thenReturn(Optional.empty());

        SendNotificationRequest req = new SendNotificationRequest();
        req.setTemplateId(templateId);

        assertThatThrownBy(() -> service.send(tenantId, req))
                .isInstanceOf(TemplateNotFoundException.class);
    }

    @Test
    void send_scheduledInFuture_savesWithScheduledStatus() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(requestRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        NotificationRequest saved = buildRequest(RequestStatus.SCHEDULED);
        when(requestRepository.save(any())).thenReturn(saved);

        SendNotificationRequest req = new SendNotificationRequest();
        req.setRecipientRef("user1");
        req.setChannel(Channel.EMAIL);
        req.setBody("Scheduled message");
        req.setScheduledAt(Instant.now().plusSeconds(3600));

        service.send(tenantId, req);

        // Should NOT publish to Kafka for scheduled notifications
        verifyNoInteractions(eventPublisher);
        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(requestRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RequestStatus.SCHEDULED);
    }

    @Test
    void send_multipleChannels_createsOneNotificationPerChannel() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(requestRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        NotificationRequest saved = buildRequest(RequestStatus.PROCESSING);
        when(requestRepository.save(any())).thenReturn(saved);
        when(notificationRepository.save(any())).thenReturn(buildNotification());

        SendNotificationRequest req = new SendNotificationRequest();
        req.setRecipientRef("user1");
        req.setEmail("u@example.com");
        req.setBody("Multi-channel");
        req.setChannels(List.of(Channel.EMAIL, Channel.SMS));

        service.send(tenantId, req);

        verify(notificationRepository, times(2)).save(any());
        verify(eventPublisher, times(2)).publishDispatch(eq(tenantId), any(UUID.class));
    }

    @Test
    void send_noChannelSpecified_throwsIllegalArgument() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(requestRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());

        SendNotificationRequest req = new SendNotificationRequest();
        req.setRecipientRef("u");
        req.setBody("no channel");

        assertThatThrownBy(() -> service.send(tenantId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void forceExhausted_nonDelivered_updatesToExhausted() {
        UUID notifId = UUID.randomUUID();
        Notification n = buildNotification();
        n.setStatus(NotificationStatus.FAILED);
        when(notificationRepository.findById(notifId)).thenReturn(Optional.of(n));
        when(notificationRepository.save(any())).thenReturn(n);

        service.forceExhausted(notifId);

        ArgumentCaptor<Notification> cap = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(NotificationStatus.EXHAUSTED);
        assertThat(cap.getValue().getNextRetryAt()).isNull();
    }

    @Test
    void forceExhausted_alreadyDelivered_isNoOp() {
        UUID notifId = UUID.randomUUID();
        Notification n = buildNotification();
        n.setStatus(NotificationStatus.DELIVERED);
        when(notificationRepository.findById(notifId)).thenReturn(Optional.of(n));

        service.forceExhausted(notifId);

        verify(notificationRepository, never()).save(any());
    }

    // --- helpers ---

    private NotificationRequest buildRequest(RequestStatus status) {
        return NotificationRequest.builder()
                .id(UUID.randomUUID()).tenant(tenant)
                .recipientRef("user").channels("EMAIL")
                .status(status).build();
    }

    private Notification buildNotification() {
        return Notification.builder()
                .id(UUID.randomUUID()).tenant(tenant)
                .channel(Channel.EMAIL).recipientAddress("u@example.com")
                .renderedBody("hi").status(NotificationStatus.PENDING).maxAttempts(5).build();
    }
}
