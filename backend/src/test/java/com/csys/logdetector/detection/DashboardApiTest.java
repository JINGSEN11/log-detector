package com.csys.logdetector.detection;

import com.csys.logdetector.detection.controller.DashboardController;
import com.csys.logdetector.detection.service.DashboardService;

import com.csys.logdetector.config.SecurityConfig;
import com.csys.logdetector.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DashboardController.class)
@Import(SecurityConfig.class)
class DashboardApiTest {
    @Autowired MockMvc mvc;
    @MockBean DashboardService service;
    @MockBean JwtService jwtService;
    @Test void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/logs/1/context")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void analystCanReadData() throws Exception {
        when(service.dashboard()).thenReturn(Map.of("eventCount", 4, "aiAnalysisCount", 3));
        when(service.context(1, 5, 5)).thenReturn(Map.of("batchId", 2, "targetLogId", 1));
        mvc.perform(get("/api/dashboard").with(user("analyst").roles("ANALYST"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventCount").value(4)).andExpect(jsonPath("$.data.aiAnalysisCount").value(3));
        mvc.perform(get("/api/logs/1/context").with(user("analyst").roles("ANALYST"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.targetLogId").value(1));
    }
}
