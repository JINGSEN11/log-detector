package com.csys.logdetector.detection;

import com.csys.logdetector.detection.controller.DetectionController;
import com.csys.logdetector.detection.controller.EventAnalysisController;
import com.csys.logdetector.detection.controller.EventWorkflowController;
import com.csys.logdetector.detection.service.DetectionService;
import com.csys.logdetector.detection.service.EventAnalysisPersistenceService;
import com.csys.logdetector.detection.service.EventWorkflowService;
import com.csys.logdetector.detection.service.AnalysisCostService;

import com.csys.logdetector.audit.controller.AuditController;
import com.csys.logdetector.audit.service.AuditService;
import com.csys.logdetector.config.SecurityConfig;
import com.csys.logdetector.security.JwtProperties;
import com.csys.logdetector.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({AuditController.class, DetectionController.class, EventAnalysisController.class, EventWorkflowController.class})
@Import({SecurityConfig.class, JwtAuthorizationApiTest.JwtTestConfig.class})
class JwtAuthorizationApiTest {
    @TestConfiguration
    static class JwtTestConfig {
        @Bean JwtService jwtService() {
            return new JwtService(new JwtProperties("test-only-secret-at-least-32-bytes-long", 120));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockBean AuditService audit;
    @MockBean DetectionService detection;
    @MockBean AnalysisCostService analysis;
    @MockBean EventAnalysisPersistenceService persistence;
    @MockBean com.csys.logdetector.security.RequestRateLimiter rateLimiter;
    @MockBean com.csys.logdetector.audit.service.AuditWriter auditWriter;
    @MockBean EventWorkflowService workflow;

    private String bearer(String role) {
        return "Bearer " + jwt.issue(7L, role.toLowerCase(), role);
    }

    @Test void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/audit")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/detection/batches/1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/events/1/analysis")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/events/1/analyses/latest")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/events/1/analyses")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/events/export.csv")).andExpect(status().isUnauthorized());
        verifyNoInteractions(audit, detection, analysis, workflow);
    }

    @Test void tamperedTokenIsRejected() throws Exception {
        String token = jwt.issue(7L, "admin", "ADMIN");
        int signatureStart = token.lastIndexOf('.') + 1;
        char first = token.charAt(signatureStart);
        String tampered = token.substring(0, signatureStart) + (first == 'A' ? 'B' : 'A') + token.substring(signatureStart + 1);
        mvc.perform(get("/api/audit").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(audit);
    }

    @Test void auditRequiresAdminRole() throws Exception {
        mvc.perform(get("/api/audit").header(HttpHeaders.AUTHORIZATION, bearer("ANALYST")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(audit);
        mvc.perform(get("/api/audit").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk());
        verify(audit).list(1, 20);
    }

    @Test void analysisCostsRequireAdmin() throws Exception {
        mvc.perform(get("/api/analysis-cost")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/analysis-cost").header(HttpHeaders.AUTHORIZATION, bearer("ANALYST"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/analysis-cost").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))).andExpect(status().isOk());
        verify(analysis).dashboard();
    }

    @Test void analystCanDetectAnalyzeAndExport() throws Exception {
        when(detection.detect(1)).thenReturn(Map.of("batchId", 1));
        when(analysis.analyze(1, 7L)).thenReturn(Map.of("id", 1));
        when(workflow.csv(7, null)).thenReturn("csv");
        when(workflow.markdown(1, 7)).thenReturn("report");
        String token = bearer("ANALYST");
        mvc.perform(post("/api/detection/batches/1").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk());
        mvc.perform(post("/api/events/1/analysis").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk());
        mvc.perform(get("/api/events/1/analyses/latest").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk());
        mvc.perform(get("/api/events/1/analyses?page=2&size=5").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk());
        verify(persistence).latest(1);
        verify(persistence).list(1, 2, 5);
        mvc.perform(get("/api/events/export.csv").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk());
        mvc.perform(get("/api/events/1/report.md").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk());
        verify(detection).detect(1);
        verify(analysis).analyze(1, 7L);
        verify(workflow).csv(7, null);
        verify(workflow).markdown(1, 7);
    }
}
