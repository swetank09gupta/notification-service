package com.dmg.notification.security;

import com.dmg.notification.config.AppProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final AppProperties appProperties;

    public String generateToken(UserPrincipal principal) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + appProperties.getJwt().getExpirationMs());

        return Jwts.builder()
                .subject(principal.getEmail())
                .claim("userId", principal.getId().toString())
                .claim("role", principal.getRole().name())
                .claim("tenantId", principal.getTenantId() != null ? principal.getTenantId().toString() : null)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey())
                .compact();
    }

    public String getEmailFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT: {}", e.getMessage());
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey signingKey() {
        byte[] keyBytes = Decoders.BASE64.decode(appProperties.getJwt().getSecret());
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
