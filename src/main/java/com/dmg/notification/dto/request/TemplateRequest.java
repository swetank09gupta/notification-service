package com.dmg.notification.dto.request;

import com.dmg.notification.domain.enums.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TemplateRequest {

    @NotBlank
    @Size(max = 255, message = "Template name must not exceed 255 characters")
    private String name;

    @NotNull
    private Channel channel;

    @Size(max = 500, message = "Subject must not exceed 500 characters")
    private String subject;

    @NotBlank
    @Size(max = 50_000, message = "Template body must not exceed 50,000 characters")
    private String body;
}
