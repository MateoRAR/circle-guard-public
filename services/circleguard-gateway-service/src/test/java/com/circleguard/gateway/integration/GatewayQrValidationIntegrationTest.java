package com.circleguard.gateway.integration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.security.Key;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("integration")
class GatewayQrValidationIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.2"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String QR_SECRET = "my-qr-secret-key-for-dev-1234567890";

    private String buildQrToken(String anonymousId) {
        Key key = Keys.hmacShaKeyFor(QR_SECRET.getBytes());
        return Jwts.builder()
                .setSubject(anonymousId)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    void validate_withClearStatus_returnsGreen() {
        String anonymousId = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set("user:status:" + anonymousId, "CLEAR");

        Map<String, String> body = Map.of("token", buildQrToken(anonymousId));
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/gate/validate", body, Map.class);

        assertEquals(200, response.getStatusCode().value());
        assertTrue((Boolean) response.getBody().get("valid"));
        assertEquals("GREEN", response.getBody().get("status"));
    }

    @Test
    void validate_withContagiedStatus_returnsRed() {
        String anonymousId = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set("user:status:" + anonymousId, "CONTAGIED");

        Map<String, String> body = Map.of("token", buildQrToken(anonymousId));
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/gate/validate", body, Map.class);

        assertEquals(200, response.getStatusCode().value());
        assertFalse((Boolean) response.getBody().get("valid"));
        assertEquals("RED", response.getBody().get("status"));
    }

    @Test
    void validate_withNoStatusInRedis_allowsEntry() {
        String anonymousId = UUID.randomUUID().toString();
        // No status set in Redis

        Map<String, String> body = Map.of("token", buildQrToken(anonymousId));
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/gate/validate", body, Map.class);

        assertEquals(200, response.getStatusCode().value());
        assertTrue((Boolean) response.getBody().get("valid"));
    }

    @Test
    void validate_withInvalidToken_returnsRed() {
        Map<String, String> body = Map.of("token", "invalid.jwt.token");
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/gate/validate", body, Map.class);

        assertEquals(200, response.getStatusCode().value());
        assertFalse((Boolean) response.getBody().get("valid"));
        assertEquals("RED", response.getBody().get("status"));
    }
}
