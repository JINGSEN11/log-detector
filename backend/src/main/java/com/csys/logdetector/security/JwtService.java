package com.csys.logdetector.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * JWT 签发与校验（jjwt 0.12.x API）。
 *
 * <p>签名算法 HS256 要求密钥至少 32 字节；密钥来自 {@code JWT_SECRET}，
 * 未配置时使用 application.yml 中的开发默认值（仅本地可用）。</p>
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final long ttlMinutes;

    public JwtService(JwtProperties properties) {
        byte[] raw = String.valueOf(properties.secret()).getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException("JWT_SECRET 至少需要 32 字节，当前只有 " + raw.length + " 字节");
        }
        this.key = Keys.hmacShaKeyFor(raw);
        this.ttlMinutes = properties.ttlMinutes() > 0 ? properties.ttlMinutes() : 120L;
    }

    public String issue(Long userId, String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttlMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    /**
     * 校验并解析 token；无效（签名错、过期、格式错）一律返回 null，由上层决定 401。
     */
    public LoginUser parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Object uid = claims.get("uid");
            Object role = claims.get("role");
            Long userId = uid == null ? null : Long.valueOf(String.valueOf(uid));
            return new LoginUser(userId, claims.getSubject(), role == null ? "USER" : String.valueOf(role));
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("JWT 校验失败：{}", ex.getMessage());
            return null;
        }
    }
}
