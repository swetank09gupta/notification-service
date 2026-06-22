package com.dmg.notification.unit;

import com.dmg.notification.domain.User;
import com.dmg.notification.domain.enums.UserRole;
import com.dmg.notification.security.JwtAuthFilter;
import com.dmg.notification.security.JwtTokenProvider;
import com.dmg.notification.security.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock JwtTokenProvider tokenProvider;
    @Mock UserDetailsService userDetailsService;
    @InjectMocks JwtAuthFilter filter;

    @BeforeEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void resetContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validToken_setsAuthentication() throws Exception {
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("user@example.com")
                .passwordHash("hash")
                .role(UserRole.TENANT_ADMIN)
                .build();
        UserPrincipal principal = new UserPrincipal(user);

        when(tokenProvider.validateToken("valid.jwt.token")).thenReturn(true);
        when(tokenProvider.getEmailFromToken("valid.jwt.token")).thenReturn("user@example.com");
        when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(principal);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo(principal);
        verify(chain).doFilter(request, response);
    }

    @Test
    void invalidToken_doesNotSetAuthentication() throws Exception {
        when(tokenProvider.validateToken("bad.token")).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bad.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void missingAuthHeader_doesNotSetAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verifyNoInteractions(tokenProvider);
    }

    @Test
    void nonBearerAuthHeader_doesNotSetAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verifyNoInteractions(tokenProvider);
    }
}
