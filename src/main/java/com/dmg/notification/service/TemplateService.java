package com.dmg.notification.service;

import com.dmg.notification.domain.Template;
import com.dmg.notification.domain.Tenant;
import com.dmg.notification.dto.request.TemplateRequest;
import com.dmg.notification.dto.response.TemplateResponse;
import com.dmg.notification.exception.TenantNotFoundException;
import com.dmg.notification.exception.TemplateNotFoundException;
import com.dmg.notification.repository.TemplateRepository;
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
public class TemplateService {

    private final TemplateRepository templateRepository;
    private final TenantRepository tenantRepository;

    @Transactional
    public TemplateResponse create(UUID tenantId, TemplateRequest req) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));

        if (templateRepository.existsByTenantIdAndNameAndChannel(tenantId, req.getName(), req.getChannel())) {
            throw new IllegalArgumentException(
                    "Template already exists: " + req.getName() + " / " + req.getChannel());
        }

        Template template = Template.builder()
                .tenant(tenant)
                .name(req.getName())
                .channel(req.getChannel())
                .subject(req.getSubject())
                .body(req.getBody())
                .active(true)
                .version(1)
                .build();
        TemplateResponse response = TemplateResponse.from(templateRepository.save(template));
        log.info("Template created tenantId={} name={} channel={}", tenantId, req.getName(), req.getChannel());
        return response;
    }

    @Transactional
    public TemplateResponse update(UUID tenantId, UUID templateId, TemplateRequest req) {
        Template template = templateRepository.findByIdAndTenantId(templateId, tenantId)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));

        template.setSubject(req.getSubject());
        template.setBody(req.getBody());
        template.setVersion(template.getVersion() + 1);
        TemplateResponse response = TemplateResponse.from(templateRepository.save(template));
        log.info("Template updated tenantId={} templateId={} version={}", tenantId, templateId, template.getVersion());
        return response;
    }

    @Transactional
    public void deactivate(UUID tenantId, UUID templateId) {
        Template template = templateRepository.findByIdAndTenantId(templateId, tenantId)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));
        template.setActive(false);
        templateRepository.save(template);
        log.info("Template deactivated tenantId={} templateId={}", tenantId, templateId);
    }

    @Transactional(readOnly = true)
    public List<TemplateResponse> list(UUID tenantId) {
        return templateRepository.findAllByTenantIdAndActiveTrue(tenantId)
                .stream().map(TemplateResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public TemplateResponse get(UUID tenantId, UUID templateId) {
        return templateRepository.findByIdAndTenantId(templateId, tenantId)
                .map(TemplateResponse::from)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));
    }
}
