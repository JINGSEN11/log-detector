package com.csys.logdetector;

import com.csys.logdetector.common.ApiErrorController;
import com.csys.logdetector.common.TraceContext;
import com.csys.logdetector.common.TraceFilter;
import com.csys.logdetector.config.SecurityConfig;
import com.csys.logdetector.security.JwtService;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {TraceErrorApiTest.TestController.class, ApiErrorController.class})
@Import({SecurityConfig.class, TraceFilter.class, TraceErrorApiTest.TestController.class})
class TraceErrorApiTest {
    @Autowired MockMvc mvc;
    @MockBean JwtService jwt;

    @RestController
    static class TestController {
        record Input(String value) {}
        @PostMapping("/api/test-trace") String post(@RequestBody Input input) { return TraceContext.currentId(); }
        @GetMapping("/api/test-error") String error() { throw new IllegalStateException("secret-stack-content"); }
    }

    private void assertTrace(MvcResult result) throws Exception {
        String trace = result.getResponse().getHeader("X-Trace-Id");
        assertNotNull(trace);
        assertTrue(trace.matches("[0-9a-f-]{36}"));
        assertTrue(result.getResponse().getContentAsString().contains("\"traceId\":\"" + trace + "\""));
        assertNull(MDC.get("traceId"));
    }

    @Test void securityErrorsHaveTraceAndIgnoreClientHeader() throws Exception {
        MvcResult unauthorized = mvc.perform(get("/api/test-trace").header("X-Trace-Id", "untrusted"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401)).andReturn();
        assertTrace(unauthorized);
        MvcResult forbidden = mvc.perform(get("/api/audit").with(user("analyst").roles("ANALYST")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403)).andReturn();
        assertTrace(forbidden);
        assertNotEquals(unauthorized.getResponse().getHeader("X-Trace-Id"), forbidden.getResponse().getHeader("X-Trace-Id"));
    }

    @Test void mvcErrorsHaveSafeUniformBodies() throws Exception {
        MvcResult bad = mvc.perform(post("/api/test-trace").with(user("a")).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400)).andReturn();
        assertTrace(bad);
        MvcResult method = mvc.perform(get("/api/test-trace").with(user("a")))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value(405)).andReturn();
        assertTrace(method);
        MvcResult media = mvc.perform(post("/api/test-trace").with(user("a")).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value(415)).andReturn();
        assertTrace(media);
        MvcResult missing = mvc.perform(get("/api/does-not-exist").with(user("a")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404)).andReturn();
        assertTrace(missing);
        MvcResult internal = mvc.perform(get("/api/test-error").with(user("a")))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500)).andReturn();
        assertTrace(internal);
        assertFalse(internal.getResponse().getContentAsString().contains("secret-stack-content"));
    }
}
