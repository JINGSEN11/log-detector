package com.csys.logdetector.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Redis + Lua 的滑动窗口限流，供多实例共享计数。
 *
 * <p>失败策略：Redis 连接异常时打开熔断 30 秒，期间调用方直接走本地限流，
 * 避免每个请求都等满超时；熔断到期后再试探一次 Redis。
 */
@Component
public class RedisWindowLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(RedisWindowLimiter.class);

    /** Redis 异常后的熔断时长，期间不再尝试连接。 */
    private static final long CIRCUIT_OPEN_MS = 30_000;

    private final StringRedisTemplate template;
    private DefaultRedisScript<List> script;
    private final AtomicLong fallbackCount = new AtomicLong();
    private volatile long circuitOpenUntil = 0L;

    public RedisWindowLimiter(StringRedisTemplate template) {
        this.template = template;
    }

    @PostConstruct
    void loadScript() {
        DefaultRedisScript<List> loaded = new DefaultRedisScript<>();
        loaded.setLocation(new ClassPathResource("lua/sliding_window.lua"));
        loaded.setResultType(List.class);
        this.script = loaded;
    }

    /** Redis 是否可用（熔断未打开）。 */
    public boolean available() {
        return System.currentTimeMillis() >= circuitOpenUntil;
    }

    /** 只读计数，对应旧的 peek：先看有没有到阈值。 */
    public long peek(String key, long windowMs) {
        return run(key, windowMs, 0, "peek");
    }

    /** 计数并占用一次配额；返回 {allowed, count, retryAfterSeconds}。 */
    public long[] hit(String key, long windowMs, int limit) {
        long[] result = runHit(key, windowMs, limit);
        return result;
    }

    public long fallbackCount() {
        return fallbackCount.get();
    }

    private long run(String key, long windowMs, int limit, String mode) {
        List<?> raw = execute(key, windowMs, limit, mode, "");
        if (raw == null || raw.size() < 2) {
            throw new IllegalStateException("限流脚本返回异常");
        }
        return ((Number) raw.get(1)).longValue();
    }

    private long[] runHit(String key, long windowMs, int limit) {
        List<?> raw = execute(key, windowMs, limit, "hit", member());
        if (raw == null || raw.size() < 3) {
            throw new IllegalStateException("限流脚本返回异常");
        }
        return new long[]{
                ((Number) raw.get(0)).longValue(),
                ((Number) raw.get(1)).longValue(),
                ((Number) raw.get(2)).longValue()
        };
    }

    private List<?> execute(String key, long windowMs, int limit, String mode, String member) {
        long now = System.currentTimeMillis();
        if (now < circuitOpenUntil) {
            throw new RedisUnavailableException("熔断中");
        }
        try {
            List<?> result = template.execute(script, List.of(key),
                    String.valueOf(now), String.valueOf(windowMs), String.valueOf(limit), mode, member);
            if (circuitOpenUntil != 0L) {
                LOG.info("Redis 限流已恢复");
                circuitOpenUntil = 0L;
            }
            return result;
        } catch (RuntimeException ex) {
            if (circuitOpenUntil == 0L) {
                fallbackCount.incrementAndGet();
                LOG.warn("Redis 限流不可用，降级本地窗口 {} 秒：{}", CIRCUIT_OPEN_MS / 1000, ex.getMessage());
            }
            circuitOpenUntil = now + CIRCUIT_OPEN_MS;
            throw new RedisUnavailableException("Redis 不可用", ex);
        }
    }

    /** 成员唯一，避免同一毫秒内的多个请求在 ZSET 里互相覆盖。 */
    private String member() {
        return System.currentTimeMillis() + "-" + ThreadLocalRandom.current().nextInt(1_000_000);
    }

    /** Redis 不可用，调用方应降级。 */
    public static class RedisUnavailableException extends RuntimeException {
        public RedisUnavailableException(String message) {
            super(message);
        }

        public RedisUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
