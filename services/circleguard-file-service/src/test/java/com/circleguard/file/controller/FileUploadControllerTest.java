package com.circleguard.file.controller;

import com.circleguard.file.service.FileStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FileUploadController.class)
class FileUploadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FileStorageService storageService;

    @Test
    void shouldUploadFileSuccessfully() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "certificate.pdf", "application/pdf", "mock content".getBytes());

        Mockito.when(storageService.saveFile(Mockito.any())).thenReturn("certificate.pdf");

        mockMvc.perform(multipart("/api/v1/files/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("certificate.pdf"));
    }

    @Test
    void uploadFile_returnsUuidPrefixedFilenameInResponse() throws Exception {
        String uuidFilename = "550e8400-e29b-41d4-a716-446655440000_report.pdf";
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "content".getBytes());

        Mockito.when(storageService.saveFile(Mockito.any())).thenReturn(uuidFilename);

        mockMvc.perform(multipart("/api/v1/files/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value(uuidFilename));
    }

    @Test
    void uploadFile_delegatesMultipartFileToStorageService() throws Exception {
        ArgumentCaptor<org.springframework.web.multipart.MultipartFile> captor =
                ArgumentCaptor.forClass(org.springframework.web.multipart.MultipartFile.class);
        MockMultipartFile file = new MockMultipartFile(
                "file", "image.png", "image/png", new byte[]{1, 2, 3});

        Mockito.when(storageService.saveFile(Mockito.any())).thenReturn("uuid_image.png");

        mockMvc.perform(multipart("/api/v1/files/upload").file(file))
                .andExpect(status().isOk());

        Mockito.verify(storageService).saveFile(captor.capture());
        assertTrue(captor.getValue().getOriginalFilename().endsWith("image.png"));
    }
}
