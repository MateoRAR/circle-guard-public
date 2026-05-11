package com.circleguard.e2e;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E: File upload returns filename with UUID prefix
 * Requires file-service running in circleguard-dev namespace.
 */
class FileUploadE2ETest extends BaseE2ETest {

    private static final int FILE_PORT = 8085;

    @Test
    void uploadFile_returnsFilenameWithUuidPrefix() {
        byte[] content = "test file content".getBytes();

        Response response = RestAssured.given()
                .baseUri(BASE_URL + ":" + FILE_PORT)
                .multiPart("file", "test-document.pdf", content, "application/pdf")
                .post("/api/v1/files/upload");

        assertEquals(200, response.getStatusCode());
        String filename = response.jsonPath().getString("filename");
        assertNotNull(filename);
        // UUID prefix pattern: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx_originalname
        assertTrue(filename.contains("_"), "Filename should have UUID prefix separated by underscore");
        assertTrue(filename.endsWith("test-document.pdf"));
    }

    @Test
    void uploadFile_withDifferentContentType_succeeds() {
        byte[] content = "image data".getBytes();

        RestAssured.given()
                .baseUri(BASE_URL + ":" + FILE_PORT)
                .multiPart("file", "photo.jpg", content, "image/jpeg")
                .post("/api/v1/files/upload")
                .then()
                .statusCode(200);
    }
}
