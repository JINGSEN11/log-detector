package com.csys.logdetector.log.service;

import com.csys.logdetector.config.SecurityConfig;
import com.csys.logdetector.log.controller.LogImportController;
import com.csys.logdetector.log.dto.LogImportResult;
import com.csys.logdetector.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LogImportController.class)
@Import(SecurityConfig.class)
class LogImportPaginationApiTest {
    @Autowired MockMvc mvc;
    @MockBean LogImportService service;
    @MockBean JwtService jwtService;

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/logs/batches/1/entries")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void authenticatedRequestUsesDefaults() throws Exception {
        when(service.getBatchEntries(1, 1, 100)).thenReturn(new LogImportResult.Page(1, 1, 100, 0, List.of()));
        mvc.perform(get("/api/logs/batches/1/entries").with(user("reader")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pageSize").value(100))
                .andExpect(jsonPath("$.data.items").isArray());
        verify(service).getBatchEntries(1, 1, 100);
    }

    @Test
    void uploadPassesSelectedFormatAndDefaults() throws Exception {
        var first = new org.springframework.mock.web.MockMultipartFile("file", "a.log", "text/plain", "data".getBytes());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/logs/import")
                .file(first).param("format", "custom").with(user("reader"))).andExpect(status().isOk());
        verify(service).importFile(any(), eq("custom"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/logs/import")
                .file(first).with(user("reader"))).andExpect(status().isOk());
        verify(service).importFile(any(), eq("auto"));
    }

    @Test
    void rejectsMalformedParameters() throws Exception {
        mvc.perform(get("/api/logs/batches/1/entries?page=abc").with(user("reader")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
