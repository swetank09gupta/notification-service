package com.dmg.notification.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {
    private int status;
    private String error;
    private String message;
    private Instant timestamp;
    private Map<String, String> fieldErrors;
}
