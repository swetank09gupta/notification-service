package com.dmg.notification.unit;

import com.dmg.notification.domain.ChannelConfig;
import com.dmg.notification.domain.Tenant;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.dto.request.ChannelConfigRequest;
import com.dmg.notification.dto.response.ChannelConfigResponse;
import com.dmg.notification.exception.TenantNotFoundException;
import com.dmg.notification.repository.ChannelConfigRepository;
import com.dmg.notification.repository.TenantRepository;
import com.dmg.notification.service.ChannelConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChannelConfigServiceTest {

    @Mock ChannelConfigRepository channelConfigRepository;
    @Mock TenantRepository tenantRepository;

    ChannelConfigService service;
    UUID tenantId;
    Tenant tenant;

    @BeforeEach
    void setUp() {
        service = new ChannelConfigService(channelConfigRepository, tenantRepository);
        tenantId = UUID.randomUUID();
        tenant = Tenant.builder().id(tenantId).name("T").apiKey("k").active(true).build();
    }

    @Test
    void list_returnsActiveConfigs() {
        ChannelConfig config = buildConfig(Channel.EMAIL, true);
        when(channelConfigRepository.findAllByTenantIdAndActiveTrue(tenantId))
                .thenReturn(List.of(config));

        List<ChannelConfigResponse> result = service.list(tenantId);
        assertThat(result).hasSize(1);
    }

    @Test
    void upsert_createsNewConfig() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(channelConfigRepository.findByTenantIdAndChannel(tenantId, Channel.EMAIL))
                .thenReturn(Optional.empty());
        ChannelConfig saved = buildConfig(Channel.EMAIL, true);
        when(channelConfigRepository.save(any())).thenReturn(saved);

        ChannelConfigRequest req = new ChannelConfigRequest();
        req.setChannel(Channel.EMAIL);
        req.setActive(true);
        req.setConfigJson("{\"smtp\":\"smtp.example.com\"}");

        ChannelConfigResponse resp = service.upsert(tenantId, req);
        assertThat(resp).isNotNull();
        verify(channelConfigRepository).save(any(ChannelConfig.class));
    }

    @Test
    void upsert_updatesExistingConfig() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        ChannelConfig existing = buildConfig(Channel.EMAIL, true);
        when(channelConfigRepository.findByTenantIdAndChannel(tenantId, Channel.EMAIL))
                .thenReturn(Optional.of(existing));
        when(channelConfigRepository.save(any())).thenReturn(existing);

        ChannelConfigRequest req = new ChannelConfigRequest();
        req.setChannel(Channel.EMAIL);
        req.setActive(false);
        req.setConfigJson("{\"updated\":true}");

        service.upsert(tenantId, req);

        ArgumentCaptor<ChannelConfig> cap = ArgumentCaptor.forClass(ChannelConfig.class);
        verify(channelConfigRepository).save(cap.capture());
        assertThat(cap.getValue().isActive()).isFalse();
    }

    @Test
    void upsert_tenantNotFound_throwsTenantNotFoundException() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        ChannelConfigRequest req = new ChannelConfigRequest();
        req.setChannel(Channel.EMAIL);
        assertThatThrownBy(() -> service.upsert(tenantId, req))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void setActive_disablesChannel() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        ChannelConfig existing = buildConfig(Channel.SMS, true);
        when(channelConfigRepository.findByTenantIdAndChannel(tenantId, Channel.SMS))
                .thenReturn(Optional.of(existing));
        when(channelConfigRepository.save(any())).thenReturn(existing);

        service.setActive(tenantId, Channel.SMS, false);

        ArgumentCaptor<ChannelConfig> cap = ArgumentCaptor.forClass(ChannelConfig.class);
        verify(channelConfigRepository).save(cap.capture());
        assertThat(cap.getValue().isActive()).isFalse();
    }

    @Test
    void setActive_createsConfigIfNotPresent() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(channelConfigRepository.findByTenantIdAndChannel(tenantId, Channel.PUSH))
                .thenReturn(Optional.empty());
        ChannelConfig created = buildConfig(Channel.PUSH, false);
        when(channelConfigRepository.save(any())).thenReturn(created);

        service.setActive(tenantId, Channel.PUSH, false);

        verify(channelConfigRepository).save(any(ChannelConfig.class));
    }

    private ChannelConfig buildConfig(Channel channel, boolean active) {
        return ChannelConfig.builder()
                .id(UUID.randomUUID()).tenant(tenant)
                .channel(channel).active(active)
                .configJson("{}").build();
    }
}
