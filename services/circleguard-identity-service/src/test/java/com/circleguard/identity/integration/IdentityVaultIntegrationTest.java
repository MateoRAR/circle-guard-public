package com.circleguard.identity.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import java.security.Key;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("integration")
class IdentityVaultIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("circleguard_identity")
            .withUsername("admin")
            .withPassword("password");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @MockBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    private static final String JWT_SECRET = "my-super-secret-dev-key-32-chars-long-12345678";

    private String buildTestToken(String authority) {
        Key key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes());
        return Jwts.builder()
                .setSubject("test-user")
                .claim("permissions", List.of(authority))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    void mapIdentity_persistsAndReturnsAnonymousId() {
        Map<String, String> body = Map.of("realIdentity", "alice@universidad.edu");
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/identities/map", body, Map.class);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody().get("anonymousId"));
    }

    @Test
    void mapIdentity_isIdempotentForSameInput() {
        Map<String, String> body = Map.of("realIdentity", "bob@universidad.edu");

        ResponseEntity<Map> first = restTemplate.postForEntity("/api/v1/identities/map", body, Map.class);
        ResponseEntity<Map> second = restTemplate.postForEntity("/api/v1/identities/map", body, Map.class);

        assertEquals(200, first.getStatusCode().value());
        assertEquals(first.getBody().get("anonymousId"), second.getBody().get("anonymousId"));
    }

    @Test
    void lookupIdentity_withPermission_returnsRealIdentity() {
        Map<String, String> mapBody = Map.of("realIdentity", "carol@universidad.edu");
        ResponseEntity<Map> mapResponse = restTemplate.postForEntity("/api/v1/identities/map", mapBody, Map.class);
        String anonymousId = (String) mapResponse.getBody().get("anonymousId");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(buildTestToken("identity:lookup"));
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map> lookupResponse = restTemplate.exchange(
                "/api/v1/identities/lookup/" + anonymousId, HttpMethod.GET, request, Map.class);

        assertEquals(200, lookupResponse.getStatusCode().value());
        assertEquals("carol@universidad.edu", lookupResponse.getBody().get("realIdentity"));
    }

    @Test
    void lookupIdentity_withoutPermission_returns403() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(buildTestToken("some:other:permission"));
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/v1/identities/lookup/non-existent-id", HttpMethod.GET, request, Map.class);

        assertEquals(403, response.getStatusCode().value());
    }
}
