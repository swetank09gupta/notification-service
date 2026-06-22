package com.dmg.notification.service;

import com.dmg.notification.domain.ChannelConfig;
import com.dmg.notification.domain.Tenant;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.dto.request.ChannelConfigRequest;
import com.dmg.notification.dto.response.ChannelConfigResponse;
import com.dmg.notification.exception.TenantNotFoundException;
import com.dmg.notification.repository.ChannelConfigRepository;
import com.dmg.notification.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelConfigService {

    private final ChannelConfigRepository channelConfigRepository;
    private final TenantRepository tenantRepository;

    @Transactional(readOnly = true)
    public List<ChannelConfigResponse> list(UUID tenantId) {
        return channelConfigRepository.findAllByTenantIdAndActiveTrue(tenantId)
                .stream()
                .map(ChannelConfigResponse::from)
                .toList();
    }

    @Transactional
    public ChannelConfigResponse upsert(UUID tenantId, ChannelConfigRequest req) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));

        ChannelConfig config = channelConfigRepository
                .findByTenantIdAndChannel(tenantId, req.getChannel())
                .orElseGet(() -> ChannelConfig.builder()
                        .tenant(tenant)
                        .channel(req.getChannel())
                        .build());

        config.setActive(req.isActive());
        config.setConfigJson(req.getConfigJson());
        ChannelConfigResponse response = ChannelConfigResponse.from(channelConfigRepository.save(config));
        log.info("Channel config upserted tenantId={} channel={} active={}", tenantId, req.getChannel(), req.isActive());
        return response;
    }

    @Transactional
    public ChannelConfigResponse setActive(UUID tenantId, Channel channel, boolean active) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));

        ChannelConfig config = channelConfigRepository
                .findByTenantIdAndChannel(tenantId, channel)
                .orElseGet(() -> ChannelConfig.builder()
                        .tenant(tenant)
                        .channel(channel)
                        .build());

        config.setActive(active);
        ChannelConfigResponse response = ChannelConfigResponse.from(channelConfigRepository.save(config));
        log.info("Channel config active={} tenantId={} channel={}", active, tenantId, channel);
        return response;
    }
}
