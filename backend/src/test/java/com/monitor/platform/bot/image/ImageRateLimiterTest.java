package com.monitor.platform.bot.image;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生图限频器：全局滑动窗口，一分钟最多 N 张。
 *
 * <p>用显式时间推进，不真的等一分钟。</p>
 */
class ImageRateLimiterTest {

    private final Instant base = Instant.parse("2026-10-11T10:00:00Z");

    /** 窗口内最多放行 maxPerWindow 张，第 N+1 张必须被拒。 */
    @Test
    void shouldRejectBeyondLimitWithinWindow() {
        ImageRateLimiter limiter = new ImageRateLimiter(3, Duration.ofMinutes(1));

        assertTrue(limiter.tryAcquire(base));
        assertTrue(limiter.tryAcquire(base.plusSeconds(10)));
        assertTrue(limiter.tryAcquire(base.plusSeconds(20)));
        assertFalse(limiter.tryAcquire(base.plusSeconds(30)));
        assertEquals(3, limiter.used());
    }

    /** 名额滑出窗口后应重新放行。 */
    @Test
    void shouldAllowAgainAfterWindowSlides() {
        ImageRateLimiter limiter = new ImageRateLimiter(3, Duration.ofMinutes(1));
        limiter.tryAcquire(base);
        limiter.tryAcquire(base.plusSeconds(10));
        limiter.tryAcquire(base.plusSeconds(20));
        assertFalse(limiter.tryAcquire(base.plusSeconds(30)));

        // 最早那次已经越过 60 秒窗口
        assertTrue(limiter.tryAcquire(base.plusSeconds(61)));
    }

    /** 被限时能告诉用户还要等多久。 */
    @Test
    void shouldReportRetryAfterWhenExhausted() {
        ImageRateLimiter limiter = new ImageRateLimiter(1, Duration.ofMinutes(1));
        assertTrue(limiter.tryAcquire(base));
        assertEquals(Duration.ZERO, limiter.retryAfter(base.plusSeconds(30)).minus(Duration.ofSeconds(30)));
        assertEquals(30, limiter.retryAfter(base.plusSeconds(30)).getSeconds());
        assertEquals(Duration.ZERO, limiter.retryAfter(base.plusSeconds(60)));
    }
}
