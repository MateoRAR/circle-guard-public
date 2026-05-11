package com.circleguard.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.Key;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenServiceTest {

    private JwtTokenService service;
    private final String secret = "my-super-secret-dev-key-32-chars-long-12345678";
    private Key verifyKey;

    @BeforeEach
    void setUp() {
        service = new JwtTokenService(secret, 3600000L);
        verifyKey = Keys.hmacShaKeyFor(secret.getBytes());
    }

    @Test
    void generateToken_subjectIsAnonymousId() {
        UUID anonymousId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken("user", null, List.of());

        String token = service.generateToken(anonymousId, auth);

        Claims claims = Jwts.parserBuilder().setSigningKey(verifyKey).build()
                .parseClaimsJws(token).getBody();
        assertEquals(anonymousId.toString(), claims.getSubject());
    }

    @Test
    void generateToken_containsPermissionsAsClaims() {
        UUID anonymousId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken("user", null,
                List.of(new SimpleGrantedAuthority("GATE_STAFF"), new SimpleGrantedAuthority("gate:scan")));

        String token = service.generateToken(anonymousId, auth);

        Claims claims = Jwts.parserBuilder().setSigningKey(verifyKey).build()
                .parseClaimsJws(token).getBody();
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) claims.get("permissions");
        assertTrue(permissions.contains("GATE_STAFF"));
        assertTrue(permissions.contains("gate:scan"));
    }

    @Test
    void generateToken_expiresAfterConfiguredMilliseconds() throws InterruptedException {
        JwtTokenService shortLived = new JwtTokenService(secret, 1L);
        UUID anonymousId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken("user", null, List.of());

        String token = shortLived.generateToken(anonymousId, auth);
        Thread.sleep(20);

        assertThrows(Exception.class, () ->
                Jwts.parserBuilder().setSigningKey(verifyKey).build().parseClaimsJws(token));
    }

    @Test
    void generateToken_rejectsTokenWithTamperedSignature() {
        UUID anonymousId = UUID.randomUUID();
        Authentication auth = new UsernamePasswordAuthenticationToken("user", null, List.of());
        String token = service.generateToken(anonymousId, auth);

        String tampered = token.substring(0, token.length() - 6) + "XXXXXX";

        assertThrows(Exception.class, () ->
                Jwts.parserBuilder().setSigningKey(verifyKey).build().parseClaimsJws(tampered));
    }
}
