package com.dmg.notification.service;

import com.dmg.notification.domain.User;
import com.dmg.notification.domain.enums.UserRole;
import com.dmg.notification.dto.request.LoginRequest;
import com.dmg.notification.dto.request.RegisterPlatformAdminRequest;
import com.dmg.notification.dto.response.AuthResponse;
import com.dmg.notification.repository.UserRepository;
import com.dmg.notification.security.JwtTokenProvider;
import com.dmg.notification.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthResponse login(LoginRequest req) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.getEmail(), req.getPassword()));
        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        String token = tokenProvider.generateToken(principal);
        log.info("Login successful email={} role={}", principal.getUsername(), principal.getRole());
        return new AuthResponse(token, principal.getRole().name(),
                principal.getTenantId() != null ? principal.getTenantId().toString() : null);
    }

    @Transactional
    public void registerPlatformAdmin(RegisterPlatformAdminRequest req) {
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new IllegalArgumentException("Email already registered: " + req.getEmail());
        }
        User admin = User.builder()
                .email(req.getEmail())
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .role(UserRole.PLATFORM_ADMIN)
                .build();
        userRepository.save(admin);
        log.info("Platform admin registered email={}", req.getEmail());
    }
}
