package com.circleguard.auth.integration;

import com.circleguard.auth.client.IdentityClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import org.springframework.security.authentication.BadCredentialsException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("integration")
class AuthLoginIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("circleguard_auth")
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
    private IdentityClient identityClient;

    // Mock LDAP so DualChainAuthProvider falls back to local DB
    @MockBean
    private LdapAuthenticationProvider ldapAuthenticationProvider;

    @Test
    void login_withValidCredentials_returnsJwtAndAnonymousId() {
        UUID anonymousId = UUID.randomUUID();
        when(identityClient.getAnonymousId("staff_guard")).thenReturn(anonymousId);
        when(ldapAuthenticationProvider.authenticate(any()))
                .thenThrow(new BadCredentialsException("LDAP unavailable in test"));

        Map<String, String> body = Map.of("username", "staff_guard", "password", "password");
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/auth/login", body, Map.class);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody().get("token"));
        assertEquals(anonymousId.toString(), response.getBody().get("anonymousId"));
        assertEquals("Bearer", response.getBody().get("type"));
    }

    @Test
    void login_withInvalidPassword_returns401() {
        when(ldapAuthenticationProvider.authenticate(any()))
                .thenThrow(new BadCredentialsException("LDAP unavailable in test"));

        Map<String, String> body = Map.of("username", "staff_guard", "password", "wrongpassword");
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/auth/login", body, Map.class);

        assertEquals(401, response.getStatusCode().value());
    }

    @Test
    void login_withUnknownUser_returns401() {
        when(ldapAuthenticationProvider.authenticate(any()))
                .thenThrow(new BadCredentialsException("LDAP unavailable in test"));

        Map<String, String> body = Map.of("username", "nobody", "password", "password");
        ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/auth/login", body, Map.class);

        assertEquals(401, response.getStatusCode().value());
    }
}
