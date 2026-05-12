package com.circleguard.promotion.integration;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.security.Key;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.allow-bean-definition-overriding=true")
@Testcontainers
@ActiveProfiles("test")
class PromotionStatusIntegrationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean @Primary
        public org.springframework.transaction.PlatformTransactionManager transactionManager() {
            return Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        }
        @Bean(name = "neo4jTransactionManager")
        public org.springframework.transaction.PlatformTransactionManager neo4jTransactionManager() {
            return Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("circleguard_promotion")
            .withUsername("admin")
            .withPassword("password");

    @Container
    @SuppressWarnings("resource")
    static Neo4jContainer<?> neo4j = new Neo4jContainer<>("neo4j:5.13")
            .withoutAuthentication();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.neo4j.uri", neo4j::getBoltUrl);
        registry.add("spring.neo4j.authentication.username", () -> "neo4j");
        registry.add("spring.neo4j.authentication.password", () -> "");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @MockBean
    private org.springframework.cache.CacheManager cacheManager;

    private static final String JWT_SECRET = "my-super-secret-dev-key-32-chars-long-12345678";

    private String buildTestToken(String authority) {
        Key key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes());
        return Jwts.builder()
                .setSubject("health-user-001")
                .claim("permissions", List.of(authority))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    void reportStatus_withHealthCenterPermission_returns204() {
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(buildTestToken("HEALTH_CENTER"));
        headers.set("Content-Type", "application/json");

        Map<String, Object> body = Map.of("anonymousId", "test-user-abc", "status", "SUSPECT");
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/api/v1/health/report", request, Void.class);

        assertTrue(response.getStatusCode().is2xxSuccessful());
    }

    @Test
    void reportStatus_withoutPermission_returns403() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(buildTestToken("STUDENT"));
        headers.set("Content-Type", "application/json");

        Map<String, Object> body = Map.of("anonymousId", "test-user-xyz", "status", "SUSPECT");
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/api/v1/health/report", request, Void.class);

        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void healthStatsEndpoint_isAccessible_withoutAuth() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                "/api/v1/health-status/stats", Map.class);

        assertTrue(response.getStatusCode().is2xxSuccessful());
    }
}
