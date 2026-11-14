package com.csys.logdetector.config;

import com.csys.logdetector.common.Result;
import com.csys.logdetector.security.JwtAuthFilter;
import com.csys.logdetector.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Spring Security 配置：使用无状态 JWT 认证，统一配置密码编码、接口访问规则和安全过滤器。
 *
 * OPTIONS 请求、登录、健康检查和错误路径无需认证；审计及分析成本接口需要管理员角色，
 * 其他请求需要认证。认证失败返回 401，权限不足返回 403，响应体使用统一 Result 结构。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // JWT 服务用于过滤器解析和验证令牌；ObjectMapper 用于输出 JSON 响应。
    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    // 通过构造方法注入 Spring 管理的依赖。
    public SecurityConfig(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    // 注册 BCrypt 密码编码器，用于生成和校验密码哈希。
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 定义请求进入业务接口前经过的 Spring Security 过滤器链和访问规则。
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 当前采用 JWT 认证，关闭 CSRF 防护, csrf与jwt认证冲突。
                .csrf(AbstractHttpConfigurer::disable)
                // 使用下方自定义的跨域配置。
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // 关闭 Spring Security 默认的 Basic 认证、表单登录和退出登录处理。用jwt无状态认证，无需登录。
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // 无状态认证：Spring Security 不通过 HTTP Session 保存认证信息。
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 规则按顺序匹配，使用第一条匹配的规则。
                .authorizeHttpRequests(registry -> registry
                        // 放行 OPTIONS 请求，以支持跨域预检；跨域请求仍需满足 CORS 配置。
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 登录、健康检查和错误路径无需认证。
                        .requestMatchers("/api/auth/login", "/api/health", "/actuator/health", "/error").permitAll()
                        // 审计和分析成本接口仅限管理员；默认检查 ROLE_ADMIN 权限。
                        .requestMatchers("/api/audit", "/api/audit/**", "/api/analysis-cost").hasRole("ADMIN")
                        // 其余请求必须通过认证。
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        // 未认证时返回 401 和统一的 JSON 响应。
                        .authenticationEntryPoint((request, response, ex) ->
                                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, Result.fail(401, "未登录或登录已过期")))
                        // 已认证但权限不足时返回 403 和统一的 JSON 响应。
                        .accessDeniedHandler((request, response, ex) ->
                                writeJson(response, HttpServletResponse.SC_FORBIDDEN, Result.fail(403, "没有访问权限"))))
                // 在指定过滤器之前解析 Bearer token，并将有效用户信息写入安全上下文。
                .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    // 创建适用于所有路径的跨域配置。
    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // 允许任意来源并允许携带凭证。c
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        // 浏览器可缓存预检结果 3600 秒,预检是OPTIONS请求。
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    // 设置状态码、UTF-8 和 JSON 类型，并将统一响应对象写入响应体。
    private void writeJson(HttpServletResponse response, int status, Result<?> body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
