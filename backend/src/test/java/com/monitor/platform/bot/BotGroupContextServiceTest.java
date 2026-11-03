package com.monitor.platform.bot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotGroupContextServiceTest {

    private static final String CONTEXT_ID = "monitor_admin-persona-1-v1-qq-group-222";

    @Mock private StringRedisTemplate redis;
    @Mock private ListOperations<String, String> listOperations;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private BotChatClientFactory chatClients;

    private BotGroupContextService service;

    @BeforeEach
    void setUp() {
        service = new BotGroupContextService(redis, chatClients);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    void shouldReadGroupSummaryFromRedis() {
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("monitor:bot:group:summary:" + CONTEXT_ID))
                .thenReturn("群成员甲提出了问题");

        assertEquals("群成员甲提出了问题", service.summary(CONTEXT_ID));
    }

    @Test
    void shouldFallbackToEmptySummaryWhenRedisFails() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertEquals("", service.summary(CONTEXT_ID));
    }

    @Test
    void shouldIgnoreMissingContextWithoutTouchingRedis() {
        service.recordTurn(null, "甲(10001)", "你好", "你好");
        service.recordTurn("  ", "甲(10001)", "你好", "你好");

        verifyNoInteractions(redis, chatClients);
    }

    @Test
    void shouldRecordSpeakerAwareTurnForRollingSummary() {
        when(chatClients.available()).thenReturn(true);
        when(redis.opsForList()).thenReturn(listOperations);
        when(listOperations.size("monitor:bot:group:pending:" + CONTEXT_ID)).thenReturn(1L);

        service.recordTurn(CONTEXT_ID, "甲(10001)", "联系 me@example.com，方案是什么？", "是 Redis 会话方案。");

        ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
        verify(listOperations).rightPush(eq("monitor:bot:group:pending:" + CONTEXT_ID), line.capture());
        verify(redis).expire(eq("monitor:bot:group:pending:" + CONTEXT_ID), any());
        assertTrue(line.getValue().contains("甲(10001)"));
        assertTrue(line.getValue().contains("Redis 会话方案"));
        assertFalse(line.getValue().contains("me@example.com"));
    }

    @Test
    void shouldGenerateRollingSummaryAfterTenTurns() {
        String pendingKey = "monitor:bot:group:pending:" + CONTEXT_ID;
        String summaryKey = "monitor:bot:group:summary:" + CONTEXT_ID;
        ChatClient summaryClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClients.available()).thenReturn(true);
        when(chatClients.getSummaryClient()).thenReturn(summaryClient);
        when(redis.opsForList()).thenReturn(listOperations);
        when(redis.opsForValue()).thenReturn(valueOperations);
        when(listOperations.size(pendingKey)).thenReturn(10L);
        when(listOperations.range(pendingKey, 0, -1)).thenReturn(List.of(
                "甲(10001)：\n第一条\n机器人：\n收到",
                "乙(10002)：\n第二条\n机器人：\n收到",
                "甲(10001)：\n第三条\n机器人：\n收到",
                "乙(10002)：\n第四条\n机器人：\n收到",
                "甲(10001)：\n第五条\n机器人：\n收到",
                "乙(10002)：\n第六条\n机器人：\n收到",
                "甲(10001)：\n第七条\n机器人：\n收到",
                "乙(10002)：\n第八条\n机器人：\n收到",
                "甲(10001)：\n第九条\n机器人：\n收到",
                "乙(10002)：\n第十条\n机器人：\n收到"));
        when(valueOperations.get(summaryKey)).thenReturn("");
        when(summaryClient.prompt().user(anyString()).call().content()).thenReturn("新的群聊摘要");

        service.recordTurn(CONTEXT_ID, "乙(10002)", "第十一条", "收到");

        verify(valueOperations, timeout(3000))
                .set(summaryKey, "新的群聊摘要", Duration.ofDays(30));
        verify(listOperations, timeout(3000)).trim(pendingKey, 10, -1);
    }

    @Test
    void shouldNotBreakReplyWhenRedisWriteFails() {
        when(chatClients.available()).thenReturn(true);
        when(redis.opsForList()).thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> service.recordTurn(CONTEXT_ID, "甲(10001)", "你好", "你好"));
    }
}
