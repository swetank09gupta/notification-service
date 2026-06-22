package com.dmg.notification.unit;

import com.dmg.notification.dto.request.BatchSendNotificationRequest;
import com.dmg.notification.dto.request.SendNotificationRequest;
import com.dmg.notification.dto.response.BatchSendResponse;
import com.dmg.notification.dto.response.NotificationRequestResponse;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.exception.DuplicateRequestException;
import com.dmg.notification.service.BatchNotificationService;
import com.dmg.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchNotificationServiceTest {

    @Mock NotificationService notificationService;

    BatchNotificationService service;
    UUID tenantId;

    @BeforeEach
    void setUp() {
        service = new BatchNotificationService(notificationService);
        tenantId = UUID.randomUUID();
    }

    @Test
    void allItemsAccepted_returnsAllAccepted() {
        when(notificationService.send(eq(tenantId), any()))
                .thenReturn(buildResponse());

        BatchSendNotificationRequest req = new BatchSendNotificationRequest();
        req.setNotifications(List.of(buildItem("key-1"), buildItem("key-2"), buildItem("key-3")));

        BatchSendResponse resp = service.sendBatch(tenantId, req);

        assertThat(resp.getTotal()).isEqualTo(3);
        assertThat(resp.getAccepted()).isEqualTo(3);
        assertThat(resp.getRejected()).isEqualTo(0);
        assertThat(resp.getResults()).hasSize(3);
        assertThat(resp.getResults()).allMatch(r -> "ACCEPTED".equals(r.getStatus()));
    }

    @Test
    void duplicateItems_countAsRejected() {
        UUID existingId = UUID.randomUUID();
        when(notificationService.send(eq(tenantId), any()))
                .thenReturn(buildResponse())
                .thenThrow(new DuplicateRequestException("dup-key", existingId))
                .thenReturn(buildResponse());

        BatchSendNotificationRequest req = new BatchSendNotificationRequest();
        req.setNotifications(List.of(buildItem("key-1"), buildItem("dup-key"), buildItem("key-3")));

        BatchSendResponse resp = service.sendBatch(tenantId, req);

        assertThat(resp.getAccepted()).isEqualTo(2);
        assertThat(resp.getRejected()).isEqualTo(1);
        assertThat(resp.getResults().get(1).getStatus()).isEqualTo("REJECTED");
        assertThat(resp.getResults().get(1).getErrorCode()).isEqualTo("DUPLICATE");
    }

    @Test
    void invalidItem_countAsRejected() {
        when(notificationService.send(eq(tenantId), any()))
                .thenThrow(new IllegalArgumentException("At least one channel must be specified"));

        BatchSendNotificationRequest req = new BatchSendNotificationRequest();
        req.setNotifications(List.of(buildItem("key-invalid")));

        BatchSendResponse resp = service.sendBatch(tenantId, req);

        assertThat(resp.getAccepted()).isEqualTo(0);
        assertThat(resp.getRejected()).isEqualTo(1);
        assertThat(resp.getResults().get(0).getErrorCode()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void unexpectedError_countAsRejectedWithInternalError() {
        when(notificationService.send(eq(tenantId), any()))
                .thenThrow(new RuntimeException("DB unavailable"));

        BatchSendNotificationRequest req = new BatchSendNotificationRequest();
        req.setNotifications(List.of(buildItem("key-1")));

        BatchSendResponse resp = service.sendBatch(tenantId, req);

        assertThat(resp.getRejected()).isEqualTo(1);
        assertThat(resp.getResults().get(0).getErrorCode()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void emptyBatch_returnsZeroCounts() {
        BatchSendNotificationRequest req = new BatchSendNotificationRequest();
        req.setNotifications(List.of());

        BatchSendResponse resp = service.sendBatch(tenantId, req);

        assertThat(resp.getTotal()).isEqualTo(0);
        assertThat(resp.getAccepted()).isEqualTo(0);
        assertThat(resp.getRejected()).isEqualTo(0);
        verifyNoInteractions(notificationService);
    }

    private SendNotificationRequest buildItem(String idempotencyKey) {
        SendNotificationRequest req = new SendNotificationRequest();
        req.setIdempotencyKey(idempotencyKey);
        req.setRecipientRef("user");
        req.setEmail("u@example.com");
        req.setChannel(Channel.EMAIL);
        req.setBody("Hello");
        return req;
    }

    private NotificationRequestResponse buildResponse() {
        return NotificationRequestResponse.builder()
                .id(UUID.randomUUID())
                .build();
    }
}
