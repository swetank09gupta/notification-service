package com.dmg.notification.unit;

import com.dmg.notification.channel.ChannelDispatcher;
import com.dmg.notification.channel.DispatchResult;
import com.dmg.notification.config.AppProperties;
import com.dmg.notification.domain.DeliveryAttempt;
import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.NotificationRequest;
import com.dmg.notification.domain.Tenant;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.domain.enums.NotificationStatus;
import com.dmg.notification.kafka.NotificationEventPublisher;
import com.dmg.notification.kafka.NotificationStatusPublisher;
import com.dmg.notification.observability.NotificationMetrics;
import com.dmg.notification.ratelimit.RateLimiterRegistry;
import com.dmg.notification.repository.DeliveryAttemptRepository;
import com.dmg.notification.repository.NotificationRepository;
import com.dmg.notification.repository.NotificationRequestRepository;
import com.dmg.notification.service.DispatchService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DispatchServiceTest {

    @Mock NotificationRepository notificationRepository;
    @Mock NotificationRequestRepository requestRepository;
    @Mock DeliveryAttemptRepository deliveryAttemptRepository;
    @Mock NotificationEventPublisher eventPublisher;
    @Mock NotificationStatusPublisher statusPublisher;
    @Mock RateLimiterRegistry rateLimiterRegistry;
    @Mock CircuitBreakerRegistry circuitBreakerRegistry;
    @Mock CircuitBreaker circuitBreaker;
    @Mock ChannelDispatcher emailDispatcher;
    @Mock NotificationMetrics metrics;

    AppProperties appProperties;
    DispatchService dispatchService;

    UUID tenantId;
    UUID notificationId;
    Tenant tenant;
    NotificationRequest request;
    Notification notification;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getRetry().setInitialDelaySeconds(30);
        appProperties.getRetry().setBackoffMultiplier(4);

        when(emailDispatcher.channel()).thenReturn(Channel.EMAIL);
        when(circuitBreakerRegistry.circuitBreaker(anyString())).thenReturn(circuitBreaker);

        dispatchService = new DispatchService(
                List.of(emailDispatcher),
                rateLimiterRegistry, circuitBreakerRegistry,
                eventPublisher, statusPublisher,
                notificationRepository, requestRepository,
                deliveryAttemptRepository, appProperties, metrics);

        tenantId = UUID.randomUUID();
        notificationId = UUID.randomUUID();
        tenant = Tenant.builder().id(tenantId).name("TestCo").apiKey("key").active(true).build();
        request = NotificationRequest.builder()
                .id(UUID.randomUUID()).tenant(tenant)
                .recipientRef("user-1").channels("EMAIL").build();
        notification = Notification.builder()
                .id(notificationId).tenant(tenant).request(request)
                .channel(Channel.EMAIL).recipientAddress("test@example.com")
                .renderedBody("Hello").status(NotificationStatus.PENDING)
                .maxAttempts(5).attemptCount(0).build();

        when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));
        when(deliveryAttemptRepository.save(any(DeliveryAttempt.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void skipsAlreadyDeliveredNotification() {
        notification.setStatus(NotificationStatus.DELIVERED);
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));

        dispatchService.executeDispatch(notificationId);

        verifyNoInteractions(rateLimiterRegistry, eventPublisher, statusPublisher, metrics);
    }

    @Test
    void skipsExhaustedNotification() {
        notification.setStatus(NotificationStatus.EXHAUSTED);
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));

        dispatchService.executeDispatch(notificationId);

        verifyNoInteractions(rateLimiterRegistry, eventPublisher, statusPublisher, metrics);
    }

    @Test
    void prerequisiteNotDelivered_reschedulesWithoutIncrementingAttemptCount() {
        request.setDependsOnIdempotencyKey("prereq-key");
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(requestRepository.isFullyDelivered("prereq-key")).thenReturn(false);

        dispatchService.executeDispatch(notificationId);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(saved.capture());
        assertThat(saved.getValue().getAttemptCount()).isEqualTo(0); // not incremented
        assertThat(saved.getValue().getNextRetryAt()).isNotNull();
        verify(metrics).recordPrerequisiteWait(Channel.EMAIL);
    }

    @Test
    void rateLimitExceeded_savesRateLimitedStatusAndIncreasesAttemptCount() {
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(requestRepository.isFullyDelivered(any())).thenReturn(true);
        when(rateLimiterRegistry.tryConsume(tenantId, Channel.EMAIL)).thenReturn(false);

        dispatchService.executeDispatch(notificationId);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(NotificationStatus.RATE_LIMITED);
        assertThat(saved.getValue().getAttemptCount()).isEqualTo(1);
        verify(metrics).recordRateLimited(Channel.EMAIL);
    }

    @Test
    void noDispatcherForChannel_setsExhausted() {
        notification.setChannel(Channel.SMS); // no SMS dispatcher registered
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(requestRepository.isFullyDelivered(any())).thenReturn(true);
        when(rateLimiterRegistry.tryConsume(tenantId, Channel.SMS)).thenReturn(true);
        when(circuitBreakerRegistry.circuitBreaker("sms")).thenReturn(circuitBreaker);

        dispatchService.executeDispatch(notificationId);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeast(1)).save(saved.capture());
        Notification last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(NotificationStatus.EXHAUSTED);
    }

    @Test
    void circuitBreakerOpen_treatedAsTransientFailure() {
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(requestRepository.isFullyDelivered(any())).thenReturn(true);
        when(rateLimiterRegistry.tryConsume(tenantId, Channel.EMAIL)).thenReturn(true);
        when(circuitBreaker.getCircuitBreakerConfig()).thenReturn(CircuitBreakerConfig.ofDefaults());
        when(circuitBreaker.getName()).thenReturn("email");
        CallNotPermittedException cbException = CallNotPermittedException.createCallNotPermittedException(circuitBreaker);
        when(circuitBreaker.executeSupplier(any())).thenThrow(cbException);

        dispatchService.executeDispatch(notificationId);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeast(1)).save(saved.capture());
        Notification last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(last.getNextRetryAt()).isNotNull();
    }

    @Test
    void transientFailure_withRetryRemaining_schedulesFailed() {
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(requestRepository.isFullyDelivered(any())).thenReturn(true);
        when(rateLimiterRegistry.tryConsume(tenantId, Channel.EMAIL)).thenReturn(true);
        when(circuitBreaker.executeSupplier(any())).thenAnswer(inv ->
                ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
        when(emailDispatcher.dispatch(notification)).thenReturn(
                DispatchResult.transientFailure("SMTP timeout"));

        dispatchService.executeDispatch(notificationId);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeast(1)).save(saved.capture());
        Notification last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(last.getNextRetryAt()).isNotNull();
        assertThat(last.getAttemptCount()).isEqualTo(1);
        verify(metrics).recordDispatch(eq(Channel.EMAIL), eq(NotificationStatus.FAILED), anyLong());
    }

    @Test
    void transientFailure_atMaxAttempts_setsExhaustedAndPublishesToDlq() {
        notification.setAttemptCount(4); // one more attempt = 5 = maxAttempts
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(requestRepository.isFullyDelivered(any())).thenReturn(true);
        when(rateLimiterRegistry.tryConsume(tenantId, Channel.EMAIL)).thenReturn(true);
        when(circuitBreaker.executeSupplier(any())).thenAnswer(inv ->
                ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
        when(emailDispatcher.dispatch(notification)).thenReturn(
                DispatchResult.transientFailure("SMTP timeout"));

        dispatchService.executeDispatch(notificationId);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeast(1)).save(saved.capture());
        Notification last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(NotificationStatus.EXHAUSTED);
        verify(eventPublisher).publishToDlq(eq(tenantId), eq(notificationId), anyString());
        verify(metrics).recordDlq(Channel.EMAIL);
    }

    @Test
    void permanentFailure_immediatelySetsExhaustedAndPublishesToDlq() {
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(requestRepository.isFullyDelivered(any())).thenReturn(true);
        when(rateLimiterRegistry.tryConsume(tenantId, Channel.EMAIL)).thenReturn(true);
        when(circuitBreaker.executeSupplier(any())).thenAnswer(inv ->
                ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
        when(emailDispatcher.dispatch(notification)).thenReturn(
                DispatchResult.permanentFailure("Invalid recipient"));

        dispatchService.executeDispatch(notificationId);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeast(1)).save(saved.capture());
        Notification last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(NotificationStatus.EXHAUSTED);
        verify(eventPublisher).publishToDlq(eq(tenantId), eq(notificationId), anyString());
    }

    @Test
    void successfulDispatch_setsDeliveredAndPublishesStatus() {
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(requestRepository.isFullyDelivered(any())).thenReturn(true);
        when(rateLimiterRegistry.tryConsume(tenantId, Channel.EMAIL)).thenReturn(true);
        when(circuitBreaker.executeSupplier(any())).thenAnswer(inv ->
                ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
        when(emailDispatcher.dispatch(notification)).thenReturn(DispatchResult.success("msg-id-123"));

        dispatchService.executeDispatch(notificationId);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeast(1)).save(saved.capture());
        Notification last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(last.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(last.getAttemptCount()).isEqualTo(1);
        assertThat(last.getNextRetryAt()).isNull();
        verify(statusPublisher).publishStatus(any(), eq(NotificationStatus.DELIVERED), isNull());
        verify(metrics).recordDispatch(eq(Channel.EMAIL), eq(NotificationStatus.DELIVERED), anyLong());
    }

    @Test
    void optimisticLockConflict_rethrowsException() {
        when(notificationRepository.findById(notificationId))
                .thenThrow(new ObjectOptimisticLockingFailureException(Notification.class, notificationId));

        assertThatThrownBy(() -> dispatchService.executeDispatch(notificationId))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void prerequisiteDelivered_proceedsToDispatch() {
        request.setDependsOnIdempotencyKey("prereq-key");
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(notification));
        when(requestRepository.isFullyDelivered("prereq-key")).thenReturn(true);
        when(rateLimiterRegistry.tryConsume(tenantId, Channel.EMAIL)).thenReturn(true);
        when(circuitBreaker.executeSupplier(any())).thenAnswer(inv ->
                ((java.util.function.Supplier<?>) inv.getArgument(0)).get());
        when(emailDispatcher.dispatch(notification)).thenReturn(DispatchResult.success("ok"));

        dispatchService.executeDispatch(notificationId);

        verify(metrics, never()).recordPrerequisiteWait(any());
        verify(metrics).recordDispatch(eq(Channel.EMAIL), eq(NotificationStatus.DELIVERED), anyLong());
    }
}
