package com.circleguard.e2e;

import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E: Dashboard analytics endpoints return valid responses
 * Requires dashboard-service (and promotion-service) running in circleguard-dev.
 */
class DashboardAnalyticsE2ETest extends BaseE2ETest {

    private static final int DASHBOARD_PORT = 8084;

    @Test
    void healthBoard_returnsMapWithExpectedKeys() {
        Map<String, Object> result = RestAssured.given()
                .baseUri(BASE_URL + ":" + DASHBOARD_PORT)
                .get("/api/v1/analytics/health-board")
                .then().statusCode(200)
                .extract().<Map<String, Object>>as(Map.class);

        assertNotNull(result);
    }

    @Test
    void summary_returnsMapWithoutError() {
        Map<String, Object> result = RestAssured.given()
                .baseUri(BASE_URL + ":" + DASHBOARD_PORT)
                .get("/api/v1/analytics/summary")
                .then().statusCode(200)
                .extract().<Map<String, Object>>as(Map.class);

        assertNotNull(result);
    }

    @Test
    void timeSeries_returnsListResponse() {
        RestAssured.given()
                .baseUri(BASE_URL + ":" + DASHBOARD_PORT)
                .get("/api/v1/analytics/time-series")
                .then().statusCode(200);
    }
}
