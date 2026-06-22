package com.dmg.notification.unit;

import com.dmg.notification.exception.DuplicateRequestException;
import com.dmg.notification.exception.GlobalExceptionHandler;
import com.dmg.notification.exception.TenantNotFoundException;
import com.dmg.notification.exception.TemplateNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void tenantNotFound_returns404() {
        UUID id = UUID.randomUUID();
        ResponseEntity<?> resp = handler.handleTenantNotFound(new TenantNotFoundException(id));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void templateNotFound_returns404() {
        UUID id = UUID.randomUUID();
        ResponseEntity<?> resp = handler.handleTemplateNotFound(new TemplateNotFoundException(id));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void noSuchElement_returns404() {
        ResponseEntity<?> resp = handler.handleNotFound(new NoSuchElementException("missing"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void duplicateRequest_returns409() {
        ResponseEntity<?> resp = handler.handleDuplicate(
                new DuplicateRequestException("dup-key", UUID.randomUUID()));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void illegalArgument_returns400() {
        ResponseEntity<?> resp = handler.handleBadArg(new IllegalArgumentException("bad input"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void authenticationException_returns401() {
        ResponseEntity<?> resp = handler.handleAuth(new BadCredentialsException("bad creds"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void accessDeniedException_returns403() {
        ResponseEntity<?> resp = handler.handleForbidden(new AccessDeniedException("denied"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unexpectedException_returns500() {
        ResponseEntity<?> resp = handler.handleUnexpected(new RuntimeException("unexpected"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
