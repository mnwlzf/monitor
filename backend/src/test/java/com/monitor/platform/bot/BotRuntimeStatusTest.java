package com.monitor.platform.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 运行时状态：计数、丢弃原因聚合、环形缓冲上限。 */
class BotRuntimeStatusTest {

    @Test
    void shouldCountInboundRepliedAndFailed() {
        BotRuntimeStatus status = new BotRuntimeStatus();

        status.inbound();
        status.inbound();
        status.replied();
        status.failed(10001L, "boom");

        assertEquals(2L, status.inboundCount());
        assertEquals(1L, status.repliedCount());
        assertEquals(1L, status.failedCount());
        assertNotNull(status.lastInboundAt());
        assertNotNull(status.lastOutboundAt());
        assertEquals(1, status.recentErrors().size());
        assertEquals("boom", status.recentErrors().get(0).message());
    }

    @Test
    void shouldAggregateDropReasonsAndKeepNewestFirst() {
        BotRuntimeStatus status = new BotRuntimeStatus();

        status.dropped(BotChannel.TEMP_SESSION, 222L, 1L, "不在白名单（群号 / QQ）");
        status.dropped(BotChannel.GROUP, 222L, 2L, "群里没 @机器人");
        status.dropped(BotChannel.GROUP, 222L, 3L, "群里没 @机器人");

        assertEquals(3L, status.droppedCount());
        assertEquals(2L, (long) status.dropReasons().get("群里没 @机器人"));
        assertEquals(1L, (long) status.dropReasons().get("不在白名单（群号 / QQ）"));
        // 最新在前
        assertEquals(3L, status.recentDrops().get(0).userId());
        assertEquals("TEMP_SESSION", status.recentDrops().get(2).channel());
    }

    @Test
    void shouldCapRecentBuffers() {
        BotRuntimeStatus status = new BotRuntimeStatus();

        for (int i = 0; i < 50; i++) {
            status.dropped(BotChannel.GROUP, 222L, (long) i, "reason");
            status.failed((long) i, "err");
        }

        assertEquals(30, status.recentDrops().size());
        assertEquals(30, status.recentErrors().size());
        // 计数不受缓冲上限影响
        assertEquals(50L, status.droppedCount());
        assertEquals(49L, status.recentDrops().get(0).userId());
    }

    @Test
    void shouldReportUptime() {
        BotRuntimeStatus status = new BotRuntimeStatus();
        assertTrue(status.uptimeSeconds() >= 0);
        assertNotNull(status.startedAt());
    }
}
