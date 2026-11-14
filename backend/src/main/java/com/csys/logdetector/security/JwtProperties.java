package com.csys.logdetector.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置：全部来自环境变量，禁止把密钥写进代码。
 *
 * <pre>
 * JWT_SECRET=至少 32 字节的随机串
 * JWT_TTL_MINUTES=120
 * </pre>
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, long ttlMinutes) {
}
