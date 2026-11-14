package com.csys.logdetector.security;

import com.csys.logdetector.audit.service.AuditWriter;
import com.csys.logdetector.auth.dto.LoginRequest;
import com.csys.logdetector.auth.service.AuthService;
import com.csys.logdetector.common.BizException;
import com.csys.logdetector.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoginAuditRateTest {
    @Test void eighthFailureIsAuditedWithoutCredentials() {
        UserMapper users = mock(UserMapper.class);
        AuditWriter audit = mock(AuditWriter.class);
        AuthService service = new AuthService(users, mock(PasswordEncoder.class), mock(JwtService.class), audit);
        RequestRateLimiter limiter = new RequestRateLimiter();
        LoginRequest request = new LoginRequest("sensitive-user", "sensitive-password");
        for (int i = 1; i <= 8; i++) {
            int expected = i == 8 ? 429 : 401;
            assertEquals(expected, assertThrows(BizException.class, () -> limiter.login("127.0.0.1", () -> service.login(request))).getCode());
        }
        verify(audit, times(8)).record(null, "LOGIN_FAILURE", "AUTH", null);
        verifyNoMoreInteractions(audit);
        assertEquals(429, assertThrows(BizException.class, () -> limiter.login("127.0.0.1", () -> service.login(request))).getCode());
        verifyNoMoreInteractions(audit);
    }
}
