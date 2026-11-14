package com.csys.logdetector.detection;

import com.csys.logdetector.detection.controller.DetectionController;
import com.csys.logdetector.detection.service.DetectionService;

import com.csys.logdetector.config.SecurityConfig;
import com.csys.logdetector.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DetectionController.class)
@Import(SecurityConfig.class)
class DetectionApiTest {
    @Autowired MockMvc mvc;
    @MockBean DetectionService service;
    @MockBean JwtService jwtService;
    @Test void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/events")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/detection/batches/1")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void filtersEventsByOptionalBatch() throws Exception {
        when(service.events(42L)).thenReturn(List.of(Map.of("id", 42)));
        mvc.perform(get("/api/events").param("batchId", "42").with(user("analyst").roles("ANALYST")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(42));
        verify(service).events(42L);
        mvc.perform(get("/api/events").param("batchId", "invalid").with(user("analyst").roles("ANALYST"))).andExpect(status().isBadRequest());
    }
    @Test void analystCanDetectAndInspect() throws Exception {
        when(service.detect(1)).thenReturn(Map.of("batchId",1,"hitCount",1,"eventCount",1));
        when(service.events(null)).thenReturn(List.of(Map.of("id",2,"ruleCodes",List.of("R1"))));
        when(service.event(2)).thenReturn(Map.of("id",2,"evidence",List.of(Map.of("logId",3,"ruleCode","R1","rawLineMasked","masked"))));
        mvc.perform(post("/api/detection/batches/1").with(user("analyst").roles("ANALYST"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.hitCount").value(1));
        mvc.perform(get("/api/events").with(user("analyst").roles("ANALYST"))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].ruleCodes[0]").value("R1"));
        mvc.perform(get("/api/events/2").with(user("analyst").roles("ANALYST"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.evidence[0].rawLineMasked").value("masked"));
    }
}
