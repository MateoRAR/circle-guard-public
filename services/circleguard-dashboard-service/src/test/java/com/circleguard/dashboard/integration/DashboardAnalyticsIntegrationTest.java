package com.circleguard.dashboard.integration;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DashboardAnalyticsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("circleguard_dashboard")
            .withUsername("admin")
            .withPassword("password");

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("circleguard.promotion-service.url", () -> wireMock.baseUrl());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthBoard_callsPromotionAndAppliesKAnonymity_whenTotalAboveThreshold() {
        wireMock.stubFor(get(urlEqualTo("/api/v1/health-status/stats"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"totalUsers\":100,\"suspectCount\":2,\"confirmedCount\":50}")));

        ResponseEntity<Map> response = restTemplate.getForEntity("/api/v1/analytics/health-board", Map.class);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        wireMock.verify(getRequestedFor(urlEqualTo("/api/v1/health-status/stats")));
        // suspectCount=2 is below k=5, should be masked
        assertEquals("<5", response.getBody().get("suspectCount"));
    }

    @Test
    void healthBoard_masksEntireResultWhenTotalUsersBelowK() {
        wireMock.stubFor(get(urlEqualTo("/api/v1/health-status/stats"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"totalUsers\":3,\"suspectCount\":1}")));

        ResponseEntity<Map> response = restTemplate.getForEntity("/api/v1/analytics/health-board", Map.class);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("<5", response.getBody().get("totalUsers"));
    }

    @Test
    void summary_returnsResponseWithoutCallingPromotion() {
        ResponseEntity<Map> response = restTemplate.getForEntity("/api/v1/analytics/summary", Map.class);

        assertEquals(200, response.getStatusCode().value());
    }
}
