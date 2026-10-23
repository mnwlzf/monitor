package com.monitor.platform.bot.error;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 错误查询限流：每个 QQ 用户每分钟最多 12 次。
 */
@Component
public class BotErrorQueryGuard {

    private static final int MAX_PER_MINUTE = 12;
    private static final long WINDOW_SECONDS = 60;
    private static final int CLEANUP_EVERY = 256;

    private final Map<Long, Deque<Instant>> history = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    public boolean allow(long userId) {
        Instant now = Instant.now();
        if ((calls.incrementAndGet() & (CLEANUP_EVERY - 1)) == 0) {
            cleanup(now.minusSeconds(WINDOW_SECONDS));
        }
        Deque<Instant> queue = history.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            Instant threshold = now.minusSeconds(WINDOW_SECONDS);
            while (!queue.isEmpty() && queue.peekFirst().isBefore(threshold)) {
                queue.pollFirst();
            }
            if (queue.size() >= MAX_PER_MINUTE) {
                return false;
            }
            queue.addLast(now);
            return true;
        }
    }

    /** 清理长时间没有查询的 QQ，避免进程长期运行后 Map 只增不减。 */
    private void cleanup(Instant threshold) {
        history.forEach((userId, queue) -> {
            synchronized (queue) {
                Instant last = queue.peekLast();
                if (last == null || last.isBefore(threshold)) {
                    history.remove(userId, queue);
                }
            }
        });
    }
}
