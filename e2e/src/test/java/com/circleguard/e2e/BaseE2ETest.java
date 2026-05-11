package com.circleguard.e2e;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;

import java.security.Key;
import java.util.List;
import java.util.Map;

public abstract class BaseE2ETest {

    protected static String BASE_URL;
    protected static final String JWT_SECRET = "my-super-secret-dev-key-32-chars-long-12345678";
    protected static final String QR_SECRET  = "my-qr-secret-key-for-dev-1234567890";

    @BeforeAll
    static void setUpBase() {
        BASE_URL = System.getProperty("base.url", "http://localhost");
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    protected static String buildToken(String subject, String... permissions) {
        Key key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes());
        return Jwts.builder()
                .setSubject(subject)
                .claim("permissions", List.of(permissions))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    // Authenticate against the real auth service and return the JWT
    protected static Map<String, String> login(String authServiceUrl, String username, String password) {
        Response response = RestAssured.given()
                .baseUri(authServiceUrl)
                .contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", password))
                .post("/api/v1/auth/login");
        response.then().statusCode(200);
        return response.jsonPath().getMap("$");
    }
}
