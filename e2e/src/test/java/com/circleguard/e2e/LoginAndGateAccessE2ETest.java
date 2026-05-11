package com.circleguard.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E: Login via auth → generate QR → validate at gate
 * Requires all services running in circleguard-dev namespace.
 * Run with: ./gradlew :e2e:test -Dbase.url=http://<ingress-host>
 */
class LoginAndGateAccessE2ETest extends BaseE2ETest {

    private static final int AUTH_PORT    = 8180;
    private static final int GATEWAY_PORT = 8087;

    @Test
    void fullLoginAndGateFlow_returnsGreenForValidUser() {
        String authUrl    = BASE_URL + ":" + AUTH_PORT;
        String gatewayUrl = BASE_URL + ":" + GATEWAY_PORT;

        // Step 1: Login
        Map<String, String> loginResult = login(authUrl, "staff_guard", "password");
        String jwt         = loginResult.get("token");
        String anonymousId = loginResult.get("anonymousId");
        assertNotNull(jwt);
        assertNotNull(anonymousId);

        // Step 2: Generate QR token
        String qrToken = RestAssured.given()
                .baseUri(authUrl)
                .header("Authorization", "Bearer " + jwt)
                .get("/api/v1/auth/qr/generate")
                .then().statusCode(200)
                .extract().path("qrToken");
        assertNotNull(qrToken);

        // Step 3: Validate at gate (Redis has no status → GREEN)
        Map<String, Object> result = RestAssured.given()
                .baseUri(gatewayUrl)
                .contentType(ContentType.JSON)
                .body(Map.of("token", qrToken))
                .post("/api/v1/gate/validate")
                .then().statusCode(200)
                .extract().<Map<String, Object>>as(Map.class);

        assertTrue((Boolean) result.get("valid"));
        assertEquals("GREEN", result.get("status"));
    }
}
