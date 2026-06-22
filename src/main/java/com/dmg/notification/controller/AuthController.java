package com.dmg.notification.controller;

import com.dmg.notification.dto.request.LoginRequest;
import com.dmg.notification.dto.request.RegisterPlatformAdminRequest;
import com.dmg.notification.dto.response.AuthResponse;
import com.dmg.notification.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @PostMapping("/platform-admin/register")
    @ResponseStatus(HttpStatus.CREATED)
    public void registerPlatformAdmin(@Valid @RequestBody RegisterPlatformAdminRequest req) {
        authService.registerPlatformAdmin(req);
    }
}
