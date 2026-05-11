package com.circleguard.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E: Identity mapping is idempotent; lookup requires permission
 * Requires identity-service running in circleguard-dev namespace.
 */
class IdentityMappingE2ETest extends BaseE2ETest {

    private static final int IDENTITY_PORT = 8083;

    @Test
    void mapIdentity_returnsAnonymousId() {
        String identityUrl = BASE_URL + ":" + IDENTITY_PORT;
        String realIdentity = "e2e-test-" + UUID.randomUUID() + "@universidad.edu";

        Map<String, Object> result = RestAssured.given()
                .baseUri(identityUrl)
                .contentType(ContentType.JSON)
                .body(Map.of("realIdentity", realIdentity))
                .post("/api/v1/identities/map")
                .then().statusCode(200)
                .extract().<Map<String, Object>>as(Map.class);

        assertNotNull(result.get("anonymousId"));
    }

    @Test
    void mapIdentity_isIdempotent() {
        String identityUrl  = BASE_URL + ":" + IDENTITY_PORT;
        String realIdentity = "idempotent-" + UUID.randomUUID() + "@universidad.edu";
        Map<String, String> body = Map.of("realIdentity", realIdentity);

        String firstId = RestAssured.given()
                .baseUri(identityUrl).contentType(ContentType.JSON).body(body)
                .post("/api/v1/identities/map")
                .then().statusCode(200).extract().path("anonymousId");

        String secondId = RestAssured.given()
                .baseUri(identityUrl).contentType(ContentType.JSON).body(body)
                .post("/api/v1/identities/map")
                .then().statusCode(200).extract().path("anonymousId");

        assertEquals(firstId, secondId);
    }

    @Test
    void lookupIdentity_withPermission_returnsRealIdentity() {
        String identityUrl  = BASE_URL + ":" + IDENTITY_PORT;
        String realIdentity = "lookup-" + UUID.randomUUID() + "@universidad.edu";

        String anonymousId = RestAssured.given()
                .baseUri(identityUrl).contentType(ContentType.JSON)
                .body(Map.of("realIdentity", realIdentity))
                .post("/api/v1/identities/map")
                .then().statusCode(200).extract().path("anonymousId");

        String token = buildToken("test-user", "identity:lookup");

        Map<String, Object> lookup = RestAssured.given()
                .baseUri(identityUrl)
                .header("Authorization", "Bearer " + token)
                .get("/api/v1/identities/lookup/" + anonymousId)
                .then().statusCode(200)
                .extract().<Map<String, Object>>as(Map.class);

        assertEquals(realIdentity, lookup.get("realIdentity"));
    }

    @Test
    void lookupIdentity_withoutToken_returns401or403() {
        String identityUrl = BASE_URL + ":" + IDENTITY_PORT;

        int status = RestAssured.given()
                .baseUri(identityUrl)
                .get("/api/v1/identities/lookup/" + UUID.randomUUID())
                .then().extract().statusCode();

        assertTrue(status == 401 || status == 403,
                "Expected 401 or 403 but got " + status);
    }
}
