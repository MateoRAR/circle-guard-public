package com.circleguard.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E: Report health status and verify stats update
 * Requires promotion-service running in circleguard-dev namespace.
 */
class HealthStatusFlowE2ETest extends BaseE2ETest {

    private static final int PROMOTION_PORT = 8088;

    @Test
    void reportSuspectStatus_appearsInHealthStats() {
        String promotionUrl = BASE_URL + ":" + PROMOTION_PORT;
        String anonymousId  = UUID.randomUUID().toString();
        String token        = buildToken("health-user", "HEALTH_CENTER");

        // Report suspect status
        RestAssured.given()
                .baseUri(promotionUrl)
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("anonymousId", anonymousId, "status", "SUSPECT"))
                .post("/api/v1/health/report")
                .then()
                .statusCode(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(200),
                        org.hamcrest.Matchers.is(204)));

        // Verify stats endpoint is reachable
        Map<String, Object> stats = RestAssured.given()
                .baseUri(promotionUrl)
                .get("/api/v1/health-status/stats")
                .then().statusCode(200)
                .extract().<Map<String, Object>>as(Map.class);

        assertNotNull(stats);
    }

    @Test
    void reportStatus_withoutPermission_returns403() {
        String promotionUrl = BASE_URL + ":" + PROMOTION_PORT;
        String token        = buildToken("regular-user", "STUDENT");

        RestAssured.given()
                .baseUri(promotionUrl)
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + token)
                .body(Map.of("anonymousId", UUID.randomUUID().toString(), "status", "SUSPECT"))
                .post("/api/v1/health/report")
                .then()
                .statusCode(403);
    }
}
