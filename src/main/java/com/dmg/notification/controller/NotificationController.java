package com.dmg.notification.controller;

import com.dmg.notification.dto.request.BatchSendNotificationRequest;
import com.dmg.notification.dto.request.SendNotificationRequest;
import com.dmg.notification.dto.response.BatchSendResponse;
import com.dmg.notification.dto.response.DeliveryAttemptResponse;
import com.dmg.notification.dto.response.NotificationRequestResponse;
import com.dmg.notification.dto.response.NotificationResponse;
import com.dmg.notification.repository.NotificationRepository;
import com.dmg.notification.security.UserPrincipal;
import com.dmg.notification.service.BatchNotificationService;
import com.dmg.notification.service.NotificationService;
import com.dmg.notification.service.ReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tenants/{tenantId}/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final BatchNotificationService batchNotificationService;
    private final NotificationRepository notificationRepository;
    private final ReportService reportService;

    @PostMapping("/send")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public NotificationRequestResponse send(@PathVariable UUID tenantId,
                                             @Valid @RequestBody SendNotificationRequest req,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        assertTenantAccess(principal, tenantId);
        return notificationService.send(tenantId, req);
    }

    /**
     * Batch send — up to 500 notifications per request.
     *
     * Use cases: flash-sale announcements, bulk transactional alerts.
     * Each item is processed independently; partial success is possible.
     * Returns per-item ACCEPTED/REJECTED status in the response body.
     */
    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.MULTI_STATUS)
    public BatchSendResponse sendBatch(@PathVariable UUID tenantId,
                                        @Valid @RequestBody BatchSendNotificationRequest req,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        assertTenantAccess(principal, tenantId);
        return batchNotificationService.sendBatch(tenantId, req);
    }

    @GetMapping("/{requestId}")
    public NotificationRequestResponse getRequest(@PathVariable UUID tenantId,
                                                   @PathVariable UUID requestId,
                                                   @AuthenticationPrincipal UserPrincipal principal) {
        assertTenantAccess(principal, tenantId);
        return notificationService.getRequest(tenantId, requestId);
    }

    @GetMapping
    public Page<NotificationRequestResponse> listRequests(@PathVariable UUID tenantId,
                                                           @PageableDefault(size = 20) Pageable pageable,
                                                           @AuthenticationPrincipal UserPrincipal principal) {
        assertTenantAccess(principal, tenantId);
        return notificationService.listRequests(tenantId, pageable);
    }

    @GetMapping("/{requestId}/deliveries")
    public List<NotificationResponse> getDeliveries(@PathVariable UUID tenantId,
                                                     @PathVariable UUID requestId,
                                                     @AuthenticationPrincipal UserPrincipal principal) {
        assertTenantAccess(principal, tenantId);
        return notificationRepository.findAllByRequestId(requestId)
                .stream().map(NotificationResponse::from).toList();
    }

    @GetMapping("/delivery/{notificationId}/attempts")
    public List<DeliveryAttemptResponse> getAttempts(@PathVariable UUID tenantId,
                                                      @PathVariable UUID notificationId,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        assertTenantAccess(principal, tenantId);
        return reportService.getAttempts(notificationId)
                .stream().map(DeliveryAttemptResponse::from).toList();
    }

    private void assertTenantAccess(UserPrincipal principal, UUID tenantId) {
        boolean isPlatformAdmin = principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_PLATFORM_ADMIN"));
        if (!isPlatformAdmin && !tenantId.equals(principal.getTenantId())) {
            throw new AccessDeniedException("Access denied to tenant: " + tenantId);
        }
    }
}
