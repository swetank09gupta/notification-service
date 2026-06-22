package com.dmg.notification.unit;

import com.dmg.notification.config.AppProperties;
import com.dmg.notification.domain.User;
import com.dmg.notification.domain.enums.UserRole;
import com.dmg.notification.security.JwtTokenProvider;
import com.dmg.notification.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    // Same Base64 secret as in application.yml dev default
    private static final String TEST_SECRET =
            "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";

    JwtTokenProvider tokenProvider;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getJwt().setSecret(TEST_SECRET);
        props.getJwt().setExpirationMs(3600_000L); // 1 hour
        tokenProvider = new JwtTokenProvider(props);
    }

    @Test
    void generateToken_producesNonEmptyToken() {
        UserPrincipal principal = buildPrincipal(UserRole.TENANT_ADMIN);
        String token = tokenProvider.generateToken(principal);
        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3); // header.payload.signature
    }

    @Test
    void validateToken_withValidToken_returnsTrue() {
        UserPrincipal principal = buildPrincipal(UserRole.PLATFORM_ADMIN);
        String token = tokenProvider.generateToken(principal);
        assertThat(tokenProvider.validateToken(token)).isTrue();
    }

    @Test
    void validateToken_withGarbageToken_returnsFalse() {
        assertThat(tokenProvider.validateToken("not.a.token")).isFalse();
    }

    @Test
    void validateToken_withExpiredToken_returnsFalse() {
        AppProperties shortExpiry = new AppProperties();
        shortExpiry.getJwt().setSecret(TEST_SECRET);
        shortExpiry.getJwt().setExpirationMs(-1000L); // already expired
        JwtTokenProvider expiredProvider = new JwtTokenProvider(shortExpiry);

        String token = expiredProvider.generateToken(buildPrincipal(UserRole.TENANT_ADMIN));
        assertThat(tokenProvider.validateToken(token)).isFalse();
    }

    @Test
    void getEmailFromToken_returnsCorrectEmail() {
        UserPrincipal principal = buildPrincipal(UserRole.TENANT_ADMIN);
        String token = tokenProvider.generateToken(principal);
        assertThat(tokenProvider.getEmailFromToken(token)).isEqualTo("user@example.com");
    }

    private UserPrincipal buildPrincipal(UserRole role) {
        User user = User.builder()
                .id(java.util.UUID.randomUUID())
                .email("user@example.com")
                .passwordHash("hash")
                .role(role)
                .build();
        return new UserPrincipal(user);
    }
}
