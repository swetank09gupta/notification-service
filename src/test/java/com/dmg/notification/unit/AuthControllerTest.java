package com.dmg.notification.unit;

import com.dmg.notification.controller.AuthController;
import com.dmg.notification.dto.response.AuthResponse;
import com.dmg.notification.exception.GlobalExceptionHandler;
import com.dmg.notification.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock AuthService authService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void login_validCredentials_returns200WithToken() throws Exception {
        when(authService.login(any())).thenReturn(new AuthResponse("jwt-token", "TENANT_ADMIN", "tenant-id"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.role").value("TENANT_ADMIN"));
    }

    @Test
    void login_invalidCredentials_returns401() throws Exception {
        when(authService.login(any())).thenThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_missingEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"secret123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerPlatformAdmin_validRequest_returns201() throws Exception {
        doNothing().when(authService).registerPlatformAdmin(any());

        mockMvc.perform(post("/api/auth/platform-admin/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@example.com\",\"password\":\"Securepass1\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void registerPlatformAdmin_duplicateEmail_returns400() throws Exception {
        doThrow(new IllegalArgumentException("Email already registered: admin@example.com"))
                .when(authService).registerPlatformAdmin(any());

        mockMvc.perform(post("/api/auth/platform-admin/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@example.com\",\"password\":\"Securepass1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email already registered: admin@example.com"));
    }
}
