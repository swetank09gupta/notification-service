package com.dmg.notification.unit;

import com.dmg.notification.domain.Template;
import com.dmg.notification.domain.Tenant;
import com.dmg.notification.domain.enums.Channel;
import com.dmg.notification.dto.request.TemplateRequest;
import com.dmg.notification.dto.response.TemplateResponse;
import com.dmg.notification.exception.TenantNotFoundException;
import com.dmg.notification.exception.TemplateNotFoundException;
import com.dmg.notification.repository.TemplateRepository;
import com.dmg.notification.repository.TenantRepository;
import com.dmg.notification.service.TemplateService;
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
class TemplateServiceTest {

    @Mock TemplateRepository templateRepository;
    @Mock TenantRepository tenantRepository;

    TemplateService service;
    UUID tenantId;
    Tenant tenant;

    @BeforeEach
    void setUp() {
        service = new TemplateService(templateRepository, tenantRepository);
        tenantId = UUID.randomUUID();
        tenant = Tenant.builder().id(tenantId).name("T").apiKey("k").active(true).build();
    }

    @Test
    void create_success_savesTemplate() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(templateRepository.existsByTenantIdAndNameAndChannel(tenantId, "welcome", Channel.EMAIL))
                .thenReturn(false);
        Template saved = buildTemplate();
        when(templateRepository.save(any())).thenReturn(saved);

        TemplateRequest req = buildRequest("welcome", Channel.EMAIL);
        TemplateResponse resp = service.create(tenantId, req);

        verify(templateRepository).save(any(Template.class));
        assertThat(resp).isNotNull();
    }

    @Test
    void create_tenantNotFound_throwsTenantNotFoundException() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(tenantId, buildRequest("t", Channel.EMAIL)))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void create_duplicateNameAndChannel_throwsIllegalArgument() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(templateRepository.existsByTenantIdAndNameAndChannel(tenantId, "welcome", Channel.EMAIL))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(tenantId, buildRequest("welcome", Channel.EMAIL)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_success_bumpVersionAndSaves() {
        UUID templateId = UUID.randomUUID();
        Template existing = buildTemplate();
        existing.setVersion(1);
        when(templateRepository.findByIdAndTenantId(templateId, tenantId)).thenReturn(Optional.of(existing));
        when(templateRepository.save(any())).thenReturn(existing);

        TemplateRequest req = buildRequest("welcome", Channel.EMAIL);
        req.setBody("Updated body");
        service.update(tenantId, templateId, req);

        ArgumentCaptor<Template> cap = ArgumentCaptor.forClass(Template.class);
        verify(templateRepository).save(cap.capture());
        assertThat(cap.getValue().getVersion()).isEqualTo(2);
        assertThat(cap.getValue().getBody()).isEqualTo("Updated body");
    }

    @Test
    void update_templateNotFound_throwsTemplateNotFoundException() {
        UUID missing = UUID.randomUUID();
        when(templateRepository.findByIdAndTenantId(missing, tenantId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(tenantId, missing, buildRequest("t", Channel.EMAIL)))
                .isInstanceOf(TemplateNotFoundException.class);
    }

    @Test
    void deactivate_setsActiveFalse() {
        UUID templateId = UUID.randomUUID();
        Template existing = buildTemplate();
        existing.setActive(true);
        when(templateRepository.findByIdAndTenantId(templateId, tenantId)).thenReturn(Optional.of(existing));
        when(templateRepository.save(any())).thenReturn(existing);

        service.deactivate(tenantId, templateId);

        ArgumentCaptor<Template> cap = ArgumentCaptor.forClass(Template.class);
        verify(templateRepository).save(cap.capture());
        assertThat(cap.getValue().isActive()).isFalse();
    }

    @Test
    void list_returnsActiveTemplates() {
        when(templateRepository.findAllByTenantIdAndActiveTrue(tenantId))
                .thenReturn(List.of(buildTemplate()));
        List<TemplateResponse> result = service.list(tenantId);
        assertThat(result).hasSize(1);
    }

    @Test
    void get_templateNotFound_throwsTemplateNotFoundException() {
        UUID missing = UUID.randomUUID();
        when(templateRepository.findByIdAndTenantId(missing, tenantId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(tenantId, missing))
                .isInstanceOf(TemplateNotFoundException.class);
    }

    private Template buildTemplate() {
        return Template.builder()
                .id(UUID.randomUUID()).tenant(tenant)
                .name("welcome").channel(Channel.EMAIL)
                .subject("Welcome!").body("Hello {{name}}!")
                .active(true).version(1).build();
    }

    private TemplateRequest buildRequest(String name, Channel channel) {
        TemplateRequest req = new TemplateRequest();
        req.setName(name);
        req.setChannel(channel);
        req.setSubject("Subject");
        req.setBody("Body {{var}}");
        return req;
    }
}
