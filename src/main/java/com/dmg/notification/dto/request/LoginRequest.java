package com.dmg.notification.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {

    @Email @NotBlank
    @Size(max = 320, message = "Email must not exceed 320 characters")
    private String email;

    @NotBlank
    @Size(max = 128, message = "Password must not exceed 128 characters")
    private String password;
}
