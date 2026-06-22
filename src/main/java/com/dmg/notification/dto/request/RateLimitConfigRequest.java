package com.dmg.notification.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RateLimitConfigRequest {

    @NotNull
    @Min(value = 1, message = "requestsPerMinute must be at least 1")
    @Max(value = 100_000, message = "requestsPerMinute must not exceed 100,000")
    private Integer requestsPerMinute;

    @NotNull
    @Min(value = 1, message = "requestsPerHour must be at least 1")
    @Max(value = 1_000_000, message = "requestsPerHour must not exceed 1,000,000")
    private Integer requestsPerHour;
}
