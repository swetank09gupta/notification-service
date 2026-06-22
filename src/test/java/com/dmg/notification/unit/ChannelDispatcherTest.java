package com.dmg.notification.unit;

import com.dmg.notification.channel.*;
import com.dmg.notification.domain.Notification;
import com.dmg.notification.domain.Tenant;
import com.dmg.notification.domain.enums.Channel;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelDispatcherTest {

    Tenant tenant = Tenant.builder().id(UUID.randomUUID()).name("T").apiKey("k").active(true).build();

    private Notification notification(Channel channel, String address) {
        return Notification.builder()
                .id(UUID.randomUUID()).tenant(tenant).channel(channel)
                .recipientAddress(address).renderedSubject("Subject")
                .renderedBody("Hello!").maxAttempts(5).build();
    }

    // ─── EmailChannelDispatcher ──────────────────────────────────────────────

    @Test
    void email_success() {
        DispatchResult r = new EmailChannelDispatcher().dispatch(notification(Channel.EMAIL, "ok@example.com"));
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getChannelResponse()).isNotBlank();
    }

    @Test
    void email_transientFailure() {
        DispatchResult r = new EmailChannelDispatcher().dispatch(notification(Channel.EMAIL, "fail-transient@test.com"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.isRetryable()).isTrue();
    }

    @Test
    void email_permanentFailure() {
        DispatchResult r = new EmailChannelDispatcher().dispatch(notification(Channel.EMAIL, "fail-permanent@test.com"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.isRetryable()).isFalse();
    }

    @Test
    void email_channelReturnsEmailEnum() {
        assertThat(new EmailChannelDispatcher().channel()).isEqualTo(Channel.EMAIL);
    }

    // ─── SmsChannelDispatcher ─────────────────────────────────────────────────

    @Test
    void sms_success() {
        DispatchResult r = new SmsChannelDispatcher().dispatch(notification(Channel.SMS, "+1234567890"));
        assertThat(r.isSuccess()).isTrue();
    }

    @Test
    void sms_transientFailure() {
        DispatchResult r = new SmsChannelDispatcher().dispatch(notification(Channel.SMS, "fail-transient-999"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.isRetryable()).isTrue();
    }

    @Test
    void whatsapp_transientFailure() {
        DispatchResult r = new WhatsAppChannelDispatcher().dispatch(notification(Channel.WHATSAPP, "fail-transient-wa:+1234"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.isRetryable()).isTrue();
    }

    @Test
    void whatsapp_permanentFailure() {
        DispatchResult r = new WhatsAppChannelDispatcher().dispatch(notification(Channel.WHATSAPP, "fail-permanent-wa:+5678"));
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.isRetryable()).isFalse();
    }

    @Test
    void sms_channelReturnsSmsEnum() {
        assertThat(new SmsChannelDispatcher().channel()).isEqualTo(Channel.SMS);
    }

    // ─── WhatsAppChannelDispatcher ────────────────────────────────────────────

    @Test
    void whatsapp_success() {
        DispatchResult r = new WhatsAppChannelDispatcher().dispatch(notification(Channel.WHATSAPP, "+1555000"));
        assertThat(r.isSuccess()).isTrue();
    }

    @Test
    void whatsapp_channelReturnsWhatsappEnum() {
        assertThat(new WhatsAppChannelDispatcher().channel()).isEqualTo(Channel.WHATSAPP);
    }

    // ─── PushChannelDispatcher ────────────────────────────────────────────────

    @Test
    void push_success() {
        DispatchResult r = new PushChannelDispatcher().dispatch(notification(Channel.PUSH, "device-token-abc"));
        assertThat(r.isSuccess()).isTrue();
    }

    @Test
    void push_channelReturnsPushEnum() {
        assertThat(new PushChannelDispatcher().channel()).isEqualTo(Channel.PUSH);
    }

    // ─── InAppChannelDispatcher ───────────────────────────────────────────────

    @Test
    void inApp_success() {
        DispatchResult r = new InAppChannelDispatcher().dispatch(notification(Channel.IN_APP, "user-id-123"));
        assertThat(r.isSuccess()).isTrue();
    }

    @Test
    void inApp_channelReturnsInAppEnum() {
        assertThat(new InAppChannelDispatcher().channel()).isEqualTo(Channel.IN_APP);
    }

    // ─── DispatchResult factory methods ──────────────────────────────────────

    @Test
    void dispatchResult_success_hasCorrectFlags() {
        DispatchResult r = DispatchResult.success("ok");
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.isRetryable()).isFalse();
        assertThat(r.getChannelResponse()).isEqualTo("ok");
        assertThat(r.getErrorMessage()).isNull();
    }

    @Test
    void dispatchResult_transientFailure_hasCorrectFlags() {
        DispatchResult r = DispatchResult.transientFailure("timeout");
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.isRetryable()).isTrue();
        assertThat(r.getErrorMessage()).isEqualTo("timeout");
        assertThat(r.getChannelResponse()).isNull();
    }

    @Test
    void dispatchResult_permanentFailure_hasCorrectFlags() {
        DispatchResult r = DispatchResult.permanentFailure("invalid");
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.isRetryable()).isFalse();
        assertThat(r.getErrorMessage()).isEqualTo("invalid");
    }
}
