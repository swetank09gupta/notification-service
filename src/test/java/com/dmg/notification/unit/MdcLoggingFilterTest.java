package com.dmg.notification.unit;

import com.dmg.notification.observability.MdcLoggingFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MdcLoggingFilterTest {

    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock FilterChain filterChain;

    MdcLoggingFilter filter = new MdcLoggingFilter();

    @AfterEach
    void cleanup() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void setsRequestIdInMdc() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/test");

        filter.doFilter(request, response, filterChain);

        // MDC is cleared after filter, but X-Request-Id header was set
        ArgumentCaptor<String> headerName = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> headerValue = ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(headerName.capture(), headerValue.capture());
        assertThat(headerName.getValue()).isEqualTo("X-Request-Id");
        assertThat(headerValue.getValue()).isNotBlank().hasSize(8);
    }

    @Test
    void setsXRequestIdResponseHeader() throws Exception {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/send");

        filter.doFilter(request, response, filterChain);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(eq("X-Request-Id"), captor.capture());
        assertThat(captor.getValue()).hasSize(8);
    }

    @Test
    void clearsMdcAfterFilterChain() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/health");

        // Verify MDC is set during filter chain execution
        doAnswer(inv -> {
            assertThat(MDC.get("requestId")).isNotNull();
            return null;
        }).when(filterChain).doFilter(request, response);

        filter.doFilter(request, response, filterChain);

        // MDC should be empty after the filter completes
        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("path")).isNull();
    }

    @Test
    void clearsMdcEvenWhenFilterChainThrows() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/error");
        doThrow(new RuntimeException("chain error")).when(filterChain).doFilter(request, response);

        try {
            filter.doFilter(request, response, filterChain);
        } catch (RuntimeException ignored) {}

        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void delegatesToFilterChain() throws Exception {
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/test");

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }
}
