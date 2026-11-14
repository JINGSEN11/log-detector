package com.csys.logdetector.auth.controller;

import com.csys.logdetector.auth.service.AuthService;

import com.csys.logdetector.auth.dto.LoginRequest;
import com.csys.logdetector.auth.dto.LoginResponse;
import com.csys.logdetector.auth.dto.UserInfo;
import com.csys.logdetector.common.Result;
import com.csys.logdetector.security.LoginUser;
import com.csys.logdetector.security.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import com.csys.logdetector.security.RequestRateLimiter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <ul>
 *   <li>{@code POST /api/auth/login} 放行，用于换取 token</li>
 *   <li>{@code GET  /api/auth/me} 需要 token，用于前端刷新时校验登录态</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RequestRateLimiter rateLimiter;

    public AuthController(AuthService authService, RequestRateLimiter rateLimiter) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return Result.ok(rateLimiter.login(servletRequest.getRemoteAddr(), () -> authService.login(request)));
    }

    @GetMapping("/me")
    public Result<UserInfo> me() {
        LoginUser user = SecurityUtils.currentUser();
        return Result.ok(new UserInfo(user.id(), user.username(), user.role()));
    }
}
