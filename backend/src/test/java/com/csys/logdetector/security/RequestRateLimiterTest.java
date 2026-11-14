package com.csys.logdetector.security;

import com.csys.logdetector.common.BizException;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RequestRateLimiterTest {
    static class MutableClock extends Clock {
        long now;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
    @Test void loginOnlyFailuresCountAndEighthFailsWith429UntilWindowExpires() {
        MutableClock clock = new MutableClock();
        RequestRateLimiter limiter = new RequestRateLimiter(clock);
        for (int i = 0; i < 20; i++) assertEquals("ok", limiter.login("a", () -> "ok"));
        for (int i = 0; i < 7; i++) assertEquals(401, assertThrows(BizException.class, () -> limiter.login("a", () -> { throw new BizException(401, "bad"); })).getCode());
        assertEquals(429, assertThrows(BizException.class, () -> limiter.login("a", () -> { throw new BizException(401, "bad"); })).getCode());
        assertEquals(429, assertThrows(BizException.class, () -> limiter.login("a", () -> "ok")).getCode());
        assertEquals("ok", limiter.login("b", () -> "ok"));
        clock.now = 300_000;
        assertEquals("ok", limiter.login("a", () -> "ok"));
    }
    @Test void twelfthAnalysisIsBlockedPerUserAndExpires() {
        MutableClock clock = new MutableClock();
        RequestRateLimiter limiter = new RequestRateLimiter(clock);
        for (int i = 0; i < 11; i++) limiter.analysis(1L);
        assertEquals(429, assertThrows(BizException.class, () -> limiter.analysis(1L)).getCode());
        limiter.analysis(2L);
        clock.now = 300_000;
        limiter.analysis(1L);
    }
    @Test void llmChatHasIndependentPerUserLimitAndExpires() {
        MutableClock clock = new MutableClock();
        RequestRateLimiter limiter = new RequestRateLimiter(clock);
        for (int i = 0; i < 11; i++) limiter.llmChat(1L);
        assertEquals(429, assertThrows(BizException.class, () -> limiter.llmChat(1L)).getCode());
        limiter.llmChat(2L);
        limiter.analysis(1L);
        clock.now = 299_999;
        assertEquals(429, assertThrows(BizException.class, () -> limiter.llmChat(1L)).getCode());
        clock.now = 300_000;
        limiter.llmChat(1L);
    }
    @Test void concurrentLlmChatCannotExceedThreshold() throws Exception {
        RequestRateLimiter limiter = new RequestRateLimiter();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger accepted = new AtomicInteger();
        try {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 40; i++) futures.add(pool.submit(() -> {
                try { limiter.llmChat(1L); accepted.incrementAndGet(); } catch (BizException ex) { assertEquals(429, ex.getCode()); }
            }));
            for (Future<?> f : futures) f.get();
            assertEquals(11, accepted.get());
        } finally { pool.shutdownNow(); }
    }
    @Test void concurrentAnalysisCannotExceedThreshold() throws Exception {
        RequestRateLimiter limiter = new RequestRateLimiter();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger accepted = new AtomicInteger();
        try {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 40; i++) futures.add(pool.submit(() -> {
                try { limiter.analysis(1L); accepted.incrementAndGet(); } catch (BizException ex) { assertEquals(429, ex.getCode()); }
            }));
            for (Future<?> f : futures) f.get();
            assertEquals(11, accepted.get());
        } finally { pool.shutdownNow(); }
    }
}
