package com.csys.logdetector.security;

import com.csys.logdetector.common.BizException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 限流门面：优先走 Redis 滑动窗口，多实例共享同一份计数；
 * Redis 不可用时自动降级到进程内窗口，保证单机仍能限流。
 *
 * <p>阈值语义与旧实现一致：登录失败 8 次/5 分钟，研判与模型连通性各 11 次/5 分钟。
 */
@Component
public class RequestRateLimiter {
    private static final long WINDOW_MS = 300_000;
    private static final int LOGIN_LIMIT = 8;
    private static final int QUOTA_LIMIT = 11;
    private static final int MAX_KEYS = 10_000;

    private static final int ALLOWED = 1;
    private static final int LIMITED = 0;
    private static final int UNAVAILABLE = -1;

    private final Map<String, ArrayDeque<Long>> loginFailures = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> analyses = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> llmChats = new HashMap<>();
    private final Clock clock;
    private final RedisWindowLimiter redis;
    private final Object[] loginLocks = new Object[256];

    /** 生产环境入口：Redis 可用则用 Redis，否则本地窗口。 */
    @Autowired
    public RequestRateLimiter(ObjectProvider<RedisWindowLimiter> redisProvider) {
        this(Clock.systemUTC(), redisProvider.getIfAvailable());
    }

    /** 单元测试用：纯本地窗口，不连 Redis。 */
    public RequestRateLimiter() { this(Clock.systemUTC(), null); }

    RequestRateLimiter(Clock clock) {
        this(clock, null);
    }

    RequestRateLimiter(Clock clock, RedisWindowLimiter redis) {
        this.clock = clock;
        this.redis = redis;
        for (int i = 0; i < loginLocks.length; i++) loginLocks[i] = new Object();
    }

    /** 序列化检查和认证，以防并发请求绕过失败阈值。仅认证失败计数。 */
    public <T> T login(String remoteAddress, Supplier<T> action) {
        String key = "rate:login:" + remoteAddress;
        if (redisPeek(key) >= LOGIN_LIMIT) throw limited();
        Object lock = loginLocks[(remoteAddress.hashCode() & 0x7fffffff) % loginLocks.length];
        synchronized (lock) {
            synchronized (loginFailures) {
                if (redis == null && events(loginFailures, remoteAddress, clock.millis()).size() >= LOGIN_LIMIT) {
                    throw limited();
                }
            }
            try {
                return action.get();
            } catch (BizException ex) {
                if (ex.getCode() == 401 || ex.getCode() == 403) {
                    int state = redisHit(key, LOGIN_LIMIT);
                    if (state == LIMITED) throw limited();
                    if (state == UNAVAILABLE) {
                        synchronized (loginFailures) {
                            ArrayDeque<Long> events = events(loginFailures, remoteAddress, clock.millis());
                            events.addLast(clock.millis());
                            if (events.size() >= LOGIN_LIMIT) throw limited();
                        }
                    }
                }
                throw ex;
            }
        }
    }

    /** 第 12 次研判请求起拒绝；计数包含失败的研判请求。 */
    public void analysis(Long userId) {
        int state = redisHit("rate:analyze:" + userId, QUOTA_LIMIT);
        if (state == LIMITED) throw limited();
        if (state == UNAVAILABLE) {
            synchronized (this) {
                ArrayDeque<Long> events = events(analyses, String.valueOf(userId), clock.millis());
                if (events.size() >= QUOTA_LIMIT) throw limited();
                events.addLast(clock.millis());
            }
        }
    }

    /** 模型连通性测试单独计数，每用户每五分钟最多 11 次，失败也计数。 */
    public void llmChat(Long userId) {
        int state = redisHit("rate:llm:" + userId, QUOTA_LIMIT);
        if (state == LIMITED) throw limited();
        if (state == UNAVAILABLE) {
            synchronized (this) {
                ArrayDeque<Long> events = events(llmChats, String.valueOf(userId), clock.millis());
                if (events.size() >= QUOTA_LIMIT) throw limited();
                events.addLast(clock.millis());
            }
        }
    }

    /** 当前是否在使用 Redis 计数，供健康检查与日志使用。 */
    public boolean usingRedis() {
        return redis != null && redis.available();
    }

    public long redisFallbackCount() {
        return redis == null ? -1 : redis.fallbackCount();
    }

    // ---- Redis 分支：异常时返回 UNAVAILABLE，由调用方走本地实现 ----

    private long redisPeek(String key) {
        if (redis == null) return 0;
        try {
            return redis.peek(key, WINDOW_MS);
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private int redisHit(String key, int limit) {
        if (redis == null) return UNAVAILABLE;
        try {
            long[] r = redis.hit(key, WINDOW_MS, limit);
            return r[0] == 1L ? ALLOWED : LIMITED;
        } catch (RuntimeException ex) {
            return UNAVAILABLE;
        }
    }

    private ArrayDeque<Long> events(Map<String, ArrayDeque<Long>> map, String key, long now) {
        map.values().removeIf(queue -> {
            while (!queue.isEmpty() && queue.peekFirst() <= now - WINDOW_MS) queue.removeFirst();
            return queue.isEmpty();
        });
        ArrayDeque<Long> existing = map.get(key);
        if (existing != null) return existing;
        if (map.size() >= MAX_KEYS) throw limited();
        ArrayDeque<Long> created = new ArrayDeque<>();
        map.put(key, created);
        return created;
    }

    private BizException limited() { return new BizException(429, "请求过于频繁，请稍后重试"); }
}
