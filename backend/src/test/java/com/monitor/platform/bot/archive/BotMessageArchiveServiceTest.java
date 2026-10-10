package com.monitor.platform.bot.archive;

import com.monitor.platform.bot.identity.BotIdentity;
import com.monitor.platform.bot.onebot.OneBotEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息存档：异步、关联 ID、失败隔离、清理。
 *
 * <p>最关键的一条是「不能拖慢回复」—— 存档是旁路，写库再慢也不能让调用方等。</p>
 */
class BotMessageArchiveServiceTest {

    private BotMessageArchiveProperties properties;
    private BotMessageArchiveRepository repository;
    private BotMessageArchiveService service;

    private static OneBotEvent event() {
        return new OneBotEvent("message", "private", "1024", null, 999L, 1L, "你好", null, null);
    }

    @BeforeEach
    void setUp() {
        properties = new BotMessageArchiveProperties();
        repository = mock(BotMessageArchiveRepository.class);
        service = new BotMessageArchiveService(properties, repository);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    /** 核心要求：写库再慢，调用方也必须立刻返回。 */
    @Test
    void shouldNotBlockCallerWhenInsertIsSlow() throws Exception {
        CountDownLatch insertStarted = new CountDownLatch(1);
        CountDownLatch releaseInsert = new CountDownLatch(1);
        doAnswer(invocation -> {
            insertStarted.countDown();
            releaseInsert.await(5, TimeUnit.SECONDS);
            return null;
        }).when(repository).insert(any());

        long started = System.nanoTime();
        String correlationId = service.recordInbound(event(), "你好", BotMessageArchiveService.KIND_TEXT,
                BotIdentity.guest());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertNotNull(correlationId, "应返回关联 ID");
        assertTrue(elapsedMs < 200, "recordInbound 不该等数据库，实际耗时 " + elapsedMs + "ms");
        assertTrue(insertStarted.await(2, TimeUnit.SECONDS), "后台线程应已开始写入");
        releaseInsert.countDown();
    }

    /** 一问一答必须能配对：两条记录共用同一个关联 ID。 */
    @Test
    void shouldShareCorrelationIdBetweenInboundAndOutbound() {
        String correlationId = service.recordInbound(event(), "你好", BotMessageArchiveService.KIND_TEXT,
                BotIdentity.guest());
        service.recordOutbound(event(), "你也好", BotMessageArchiveService.KIND_TEXT,
                BotIdentity.guest(), "CHAT", correlationId);

        awaitTrue(() -> {
            try {
                verify(repository, timeout(1)).insert(any());
                return true;
            } catch (AssertionError ex) {
                return false;
            }
        }, 3000);

        ArgumentCaptor<BotMessageArchiveEntity> captor = ArgumentCaptor.forClass(BotMessageArchiveEntity.class);
        verify(repository, timeout(3000).times(2)).insert(captor.capture());
        List<BotMessageArchiveEntity> saved = captor.getAllValues();
        assertEquals(2, saved.size());
        assertEquals(correlationId, saved.get(0).getCorrelationId());
        assertEquals(correlationId, saved.get(1).getCorrelationId());
        assertEquals(BotMessageArchiveService.DIRECTION_IN, saved.get(0).getDirection());
        assertEquals(BotMessageArchiveService.DIRECTION_OUT, saved.get(1).getDirection());
    }

    /** 存档时要把「当时是什么身份」一起记下来，日后才能解释为什么那样回答。 */
    @Test
    void shouldSnapshotIdentityAndPersona() {
        String correlationId = service.recordInbound(event(), "平台余额",
                BotMessageArchiveService.KIND_COMMAND, new BotIdentity(true, true, "a@b.com"));
        service.recordOutbound(event(), "余额 12.34", BotMessageArchiveService.KIND_TEXT,
                new BotIdentity(true, true, "a@b.com"), "MONITOR_ADMIN", correlationId);

        ArgumentCaptor<BotMessageArchiveEntity> captor = ArgumentCaptor.forClass(BotMessageArchiveEntity.class);
        verify(repository, timeout(3000).times(2)).insert(captor.capture());
        BotMessageArchiveEntity inbound = captor.getAllValues().get(0);
        assertEquals("ADMIN", inbound.getSenderRole());
        assertEquals("a@b.com", inbound.getSenderEmail());
        assertEquals("1024", inbound.getMessageId());
        assertEquals("MONITOR_ADMIN", captor.getAllValues().get(1).getPersona());
    }

    /** 数据库出错不能把异常抛回业务线程。 */
    @Test
    void shouldSwallowInsertFailure() {
        doThrow(new IllegalStateException("db down")).when(repository).insert(any());

        String correlationId = service.recordInbound(event(), "你好", BotMessageArchiveService.KIND_TEXT,
                BotIdentity.guest());

        assertNotNull(correlationId, "写入失败也应正常返回，不影响回复");
    }

    @Test
    void shouldSkipWhenDisabled() {
        properties.setEnabled(false);

        assertNull(service.recordInbound(event(), "你好", BotMessageArchiveService.KIND_TEXT, BotIdentity.guest()));
        service.recordOutbound(event(), "回复", BotMessageArchiveService.KIND_TEXT, BotIdentity.guest(),
                "CHAT", "abc");
        verify(repository, never()).insert(any());
    }

    @Test
    void shouldCleanupOnlyWhenRetentionPositive() {
        properties.setRetentionDays(0);
        assertEquals(0, service.cleanup());
        verify(repository, never()).deleteBefore(any());

        properties.setRetentionDays(30);
        when(repository.deleteBefore(any())).thenReturn(7);
        assertEquals(7, service.cleanup());
        verify(repository).deleteBefore(any());
    }

    @Test
    void shouldClampSearchLimitToMaxPageSize() {
        properties.setMaxPageSize(50);
        service.search(null, null, null, null, null, 9999);
        verify(repository).search(eq(null), eq(null), eq(null), eq(null), eq(null), eq(50));
    }

    private static void awaitTrue(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @SuppressWarnings("unused")
    private static OffsetDateTime unused() {
        return null;
    }
}