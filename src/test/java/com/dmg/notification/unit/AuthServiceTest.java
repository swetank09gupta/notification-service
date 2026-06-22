package com.dmg.notification.unit;

import com.dmg.notification.domain.User;
import com.dmg.notification.domain.enums.UserRole;
import com.dmg.notification.dto.request.LoginRequest;
import com.dmg.notification.dto.request.RegisterPlatformAdminRequest;
import com.dmg.notification.dto.response.AuthResponse;
import com.dmg.notification.repository.UserRepository;
import com.dmg.notification.security.JwtTokenProvider;
import com.dmg.notification.security.UserPrincipal;
import com.dmg.notification.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock AuthenticationManager authenticationManager;
    @Mock JwtTokenProvider tokenProvider;
    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock Authentication authentication;

    AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(authenticationManager, tokenProvider, userRepository, passwordEncoder);
    }

    @Test
    void login_success_returnsTokenAndRole() {
        User user = User.builder().id(UUID.randomUUID()).email("admin@co.com")
                .passwordHash("hash").role(UserRole.PLATFORM_ADMIN).build();
        UserPrincipal principal = new UserPrincipal(user);

        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(tokenProvider.generateToken(principal)).thenReturn("jwt-token");

        LoginRequest req = new LoginRequest();
        req.setEmail("admin@co.com");
        req.setPassword("secret");

        AuthResponse resp = authService.login(req);

        assertThat(resp.getToken()).isEqualTo("jwt-token");
        assertThat(resp.getRole()).isEqualTo("PLATFORM_ADMIN");
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }

    @Test
    void login_withTenantUser_includesTenantIdInResponse() {
        UUID tenantId = UUID.randomUUID();
        User user = User.builder().id(UUID.randomUUID()).email("admin@tenant.com")
                .passwordHash("hash").role(UserRole.TENANT_ADMIN)
                .tenant(com.dmg.notification.domain.Tenant.builder()
                        .id(tenantId).name("T").apiKey("k").active(true).build())
                .build();
        UserPrincipal principal = new UserPrincipal(user);
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(tokenProvider.generateToken(principal)).thenReturn("jwt");

        AuthResponse resp = authService.login(new LoginRequest());

        assertThat(resp.getTenantId()).isEqualTo(tenantId.toString());
    }

    @Test
    void registerPlatformAdmin_success_savesHashedPassword() {
        when(userRepository.existsByEmail("new@co.com")).thenReturn(false);
        when(passwordEncoder.encode("pass123")).thenReturn("hashed");

        RegisterPlatformAdminRequest req = new RegisterPlatformAdminRequest();
        req.setEmail("new@co.com");
        req.setPassword("pass123");

        authService.registerPlatformAdmin(req);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("new@co.com");
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("hashed");
        assertThat(captor.getValue().getRole()).isEqualTo(UserRole.PLATFORM_ADMIN);
    }

    @Test
    void registerPlatformAdmin_duplicateEmail_throwsIllegalArgument() {
        when(userRepository.existsByEmail("dup@co.com")).thenReturn(true);

        RegisterPlatformAdminRequest req = new RegisterPlatformAdminRequest();
        req.setEmail("dup@co.com");
        req.setPassword("pass");

        assertThatThrownBy(() -> authService.registerPlatformAdmin(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dup@co.com");
    }
}
