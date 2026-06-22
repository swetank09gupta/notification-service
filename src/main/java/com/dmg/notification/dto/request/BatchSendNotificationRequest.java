package com.dmg.notification.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class BatchSendNotificationRequest {

    @NotEmpty(message = "Batch must contain at least one notification")
    @Size(max = 500, message = "Batch size cannot exceed 500 per request")
    private List<@Valid SendNotificationRequest> notifications;
}
