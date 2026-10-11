package com.monitor.platform.bot.image;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 滑动窗口限频器。
 *
 * <p>生图是全局共享的：不管是谁、在哪个群，一分钟内合计最多出 {@code maxPerWindow} 张，
 * 否则免费/低成本额度会被一句话刷光。</p>
 *
 * <p>{@link #tryAcquire(Instant)} 接收显式时间，方便单测推进时钟，
 * 不用真的等一分钟。</p>
 */
public class ImageRateLimiter {

    private final int maxPerWindow;
    private final Duration window;
    private final Deque<Instant> accepted = new ArrayDeque<>();

    public ImageRateLimiter(int maxPerWindow, Duration window) {
        this.maxPerWindow = Math.max(1, maxPerWindow);
        this.window = window == null || window.isNegative() || window.isZero()
                ? Duration.ofMinutes(1)
                : window;
    }

    /** 现在能不能出图；能就占用一个名额。 */
    public synchronized boolean tryAcquire() {
        return tryAcquire(Instant.now());
    }

    /** 同上，但用调用方给的时间（单测用）。 */
    public synchronized boolean tryAcquire(Instant now) {
        evict(now);
        if (accepted.size() >= maxPerWindow) {
            return false;
        }
        accepted.addLast(now);
        return true;
    }

    /** 还要等多久才可能有名额；现在就有名额时返回 {@link Duration#ZERO}。 */
    public synchronized Duration retryAfter() {
        return retryAfter(Instant.now());
    }

    /** 同上，但用调用方给的时间（单测用）。 */
    public synchronized Duration retryAfter(Instant now) {
        evict(now);
        if (accepted.size() < maxPerWindow || accepted.isEmpty()) {
            return Duration.ZERO;
        }
        Instant oldest = accepted.peekFirst();
        Duration waited = Duration.between(oldest, now);
        Duration remaining = window.minus(waited);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /** 当前窗口内已占用的名额。 */
    public synchronized int used() {
        evict(Instant.now());
        return accepted.size();
    }

    private void evict(Instant now) {
        Instant cutoff = now.minus(window);
        while (!accepted.isEmpty() && !accepted.peekFirst().isAfter(cutoff)) {
            accepted.removeFirst();
        }
    }
}
