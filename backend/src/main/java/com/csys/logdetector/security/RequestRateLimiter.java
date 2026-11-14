package com.csys.logdetector.security;

import com.csys.logdetector.common.BizException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** 单实例滑动窗口；重启后计数清零，不读取客户端转发头。 */
@Component
public class RequestRateLimiter {
    private static final long WINDOW_MS = 300_000;
    private static final int MAX_KEYS = 10_000;
    private final Map<String, ArrayDeque<Long>> loginFailures = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> analyses = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> llmChats = new HashMap<>();
    private final Clock clock;
    private final Object[] loginLocks = new Object[256];

    public RequestRateLimiter() { this(Clock.systemUTC()); }
    RequestRateLimiter(Clock clock) {
        this.clock = clock;
        for (int i = 0; i < loginLocks.length; i++) loginLocks[i] = new Object();
    }

    /** 序列化检查和认证，以防并发请求绕过失败阈值。仅认证失败计数。 */
    public <T> T login(String remoteAddress, Supplier<T> action) {
        Object lock = loginLocks[(remoteAddress.hashCode() & 0x7fffffff) % loginLocks.length];
        synchronized (lock) {
            synchronized (loginFailures) {
                if (events(loginFailures, remoteAddress, clock.millis()).size() >= 8) throw limited();
            }
            try {
                return action.get();
            } catch (BizException ex) {
                if (ex.getCode() == 401 || ex.getCode() == 403) {
                    synchronized (loginFailures) {
                        ArrayDeque<Long> events = events(loginFailures, remoteAddress, clock.millis());
                        events.addLast(clock.millis());
                        if (events.size() >= 8) throw limited();
                    }
                }
                throw ex;
            }
        }
    }

    /** 第 12 次研判请求起拒绝；计数包含失败的研判请求。 */
    public synchronized void analysis(Long userId) {
        ArrayDeque<Long> events = events(analyses, String.valueOf(userId), clock.millis());
        if (events.size() >= 11) throw limited();
        events.addLast(clock.millis());
    }

    /** 模型连通性测试单独计数，每用户每五分钟最多 11 次，失败也计数。 */
    public synchronized void llmChat(Long userId) {
        ArrayDeque<Long> events = events(llmChats, String.valueOf(userId), clock.millis());
        if (events.size() >= 11) throw limited();
        events.addLast(clock.millis());
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
