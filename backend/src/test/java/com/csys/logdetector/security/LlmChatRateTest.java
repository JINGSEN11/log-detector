package com.csys.logdetector.security;

import com.csys.logdetector.common.BizException;
import com.csys.logdetector.llm.controller.LlmController;
import com.csys.logdetector.llm.dto.LlmChatRequest;
import com.csys.logdetector.llm.service.LlmService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmChatRateTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void blocksBeforeCallingModelAndCountsFailures() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(9L, "user", "ANALYST"), null));
        LlmService service = mock(LlmService.class);
        when(service.chat("test")).thenThrow(new IllegalStateException("model unavailable"));
        LlmController controller = new LlmController(service, new RequestRateLimiter());
        LlmChatRequest request = new LlmChatRequest("test");
        for (int i = 0; i < 11; i++) {
            assertThrows(IllegalStateException.class, () -> controller.chat(request));
        }
        assertEquals(429, assertThrows(BizException.class, () -> controller.chat(request)).getCode());
        verify(service, times(11)).chat("test");
    }
}
