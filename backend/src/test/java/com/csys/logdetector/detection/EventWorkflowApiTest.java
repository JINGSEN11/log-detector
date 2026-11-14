package com.csys.logdetector.detection;

import com.csys.logdetector.detection.controller.EventWorkflowController;
import com.csys.logdetector.detection.service.EventWorkflowService;

import com.csys.logdetector.config.SecurityConfig;
import com.csys.logdetector.security.JwtService;
import com.csys.logdetector.security.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EventWorkflowController.class)
@Import(SecurityConfig.class)
class EventWorkflowApiTest {
    @Autowired MockMvc mvc;
    @MockBean EventWorkflowService service;
    @MockBean JwtService jwt;
    private UsernamePasswordAuthenticationToken analyst() {
        return new UsernamePasswordAuthenticationToken(new LoginUser(7L,"analyst","ANALYST"), null, List.of(new SimpleGrantedAuthority("ROLE_ANALYST")));
    }
    @Test void rejectsUnauthenticatedRequests() throws Exception {
        mvc.perform(post("/api/events/1/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"NEW\",\"note\":\"ok\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/events/1/timeline")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/events/export.csv")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/events/1/report.md")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void filtersCsvByOptionalBatch() throws Exception {
        when(service.csv(7, 42L)).thenReturn("filtered");
        mvc.perform(get("/api/events/export.csv").param("batchId", "42").with(authentication(analyst())))
                .andExpect(status().isOk()).andExpect(content().string("filtered"));
        verify(service).csv(7, 42L);
        mvc.perform(get("/api/events/export.csv").param("batchId", "invalid").with(authentication(analyst()))).andExpect(status().isBadRequest());
    }
    @Test void usesAuthenticatedActorAndDownloadHeaders() throws Exception {
        when(service.updateStatus(1,"CLOSED","note",7)).thenReturn(Map.of("id",1));
        when(service.csv(7, null)).thenReturn("test");
        when(service.markdown(1,7)).thenReturn("report");
        mvc.perform(post("/api/events/1/status").with(authentication(analyst())).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\",\"note\":\"note\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(1));
        mvc.perform(get("/api/events/export.csv").with(authentication(analyst()))).andExpect(status().isOk()).andExpect(header().string("Content-Disposition", "attachment; filename=\"events.csv\""));
        mvc.perform(get("/api/events/1/report.md").with(authentication(analyst()))).andExpect(status().isOk());
        verify(service).updateStatus(1,"CLOSED","note",7); verify(service).csv(7, null); verify(service).markdown(1,7);
    }
}
