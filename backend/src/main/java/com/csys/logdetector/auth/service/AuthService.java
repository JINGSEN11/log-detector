package com.csys.logdetector.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.csys.logdetector.audit.service.AuditWriter;
import com.csys.logdetector.auth.dto.LoginRequest;
import com.csys.logdetector.auth.dto.LoginResponse;
import com.csys.logdetector.common.BizException;
import com.csys.logdetector.security.JwtService;
import com.csys.logdetector.user.entity.User;
import com.csys.logdetector.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录校验：查库 + BCrypt 比对 + 签发 JWT。
 *
 * <p>安全要点：用户不存在与密码错误返回同一提示，避免暴露账号是否存在；
 * 失败日志只记录用户名，不记录密码。</p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditWriter auditWriter;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService, AuditWriter auditWriter) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditWriter = auditWriter;
    }

    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, request.username()));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("登录失败");
            auditWriter.record(null, "LOGIN_FAILURE", "AUTH", null);
            throw new BizException(401, "用户名或密码错误");
        }
        if (Boolean.FALSE.equals(user.getEnabled())) {
            auditWriter.record(null, "LOGIN_FAILURE", "AUTH", null);
            throw new BizException(403, "账号已被禁用，请联系管理员");
        }
        String token = jwtService.issue(user.getId(), user.getUsername(), user.getRole());
        auditWriter.record(user.getId(), "LOGIN_SUCCESS", "AUTH", null);
        log.info("登录成功：username={} role={}", user.getUsername(), user.getRole());
        return new LoginResponse(token, user.getUsername(), user.getRole());
    }
}
