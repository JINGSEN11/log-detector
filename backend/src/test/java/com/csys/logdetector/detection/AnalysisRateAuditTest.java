package com.csys.logdetector.detection;

import com.csys.logdetector.detection.controller.EventAnalysisController;
import com.csys.logdetector.detection.service.EventAnalysisPersistenceService;
import com.csys.logdetector.detection.service.AnalysisCostService;

import com.csys.logdetector.audit.service.AuditWriter;
import com.csys.logdetector.common.BizException;
import com.csys.logdetector.security.LoginUser;
import com.csys.logdetector.security.RequestRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisRateAuditTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void limitsByAuthenticatedUserAndAuditsWithoutResultContents() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(9L, "user", "ANALYST"), null));
        AnalysisCostService service = mock(AnalysisCostService.class);
        AuditWriter audit = mock(AuditWriter.class);
        when(service.analyze(42, 9L)).thenReturn(Map.of("secret", "do not audit"));
        EventAnalysisPersistenceService persistence = mock(EventAnalysisPersistenceService.class);
        when(persistence.save(eq(42L), eq(9L), anyMap())).thenAnswer(invocation -> invocation.getArgument(2));
        EventAnalysisController controller = new EventAnalysisController(service, persistence, new RequestRateLimiter(), audit);
        for (int i = 0; i < 11; i++) controller.analyze(42);
        assertEquals(429, assertThrows(BizException.class, () -> controller.analyze(42)).getCode());
        verify(service, times(11)).analyze(42, 9L);
        verifyNoInteractions(persistence);
        verify(audit, times(11)).record(9L, "ANALYSIS_SUCCESS", "EVENT", "42");
        verifyNoMoreInteractions(audit);
    }
    @Test void failureIsAuditedWithoutExceptionDetails() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(9L, "user", "ANALYST"), null));
        AnalysisCostService service = mock(AnalysisCostService.class);
        AuditWriter audit = mock(AuditWriter.class);
        when(service.analyze(42, 9L)).thenThrow(new IllegalStateException("sensitive"));
        EventAnalysisController controller = new EventAnalysisController(service, mock(EventAnalysisPersistenceService.class), new RequestRateLimiter(), audit);
        assertThrows(IllegalStateException.class, () -> controller.analyze(42));
        verify(audit).record(9L, "ANALYSIS_FAILURE", "EVENT", "42");
        verifyNoMoreInteractions(audit);
    }
}
