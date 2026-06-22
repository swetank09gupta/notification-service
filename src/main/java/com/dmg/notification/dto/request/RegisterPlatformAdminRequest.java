package com.dmg.notification.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterPlatformAdminRequest {

    @Email @NotBlank
    @Size(max = 320, message = "Email must not exceed 320 characters")
    private String email;

    @NotBlank
    @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
             message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit")
    private String password;
}
