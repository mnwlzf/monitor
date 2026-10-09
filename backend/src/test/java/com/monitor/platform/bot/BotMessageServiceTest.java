package com.monitor.platform.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.bot.onebot.OneBotEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 机器人消息路由：白名单、@ 判断、命令分发与回复。
 *
 * <p>这里刻意不提供 {@link ChatClient.Builder}，验证「没配大模型也能用命令」。</p>
 */
class BotMessageServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private OneBotClient client;
    private MonitorChatTools tools;
    private BotMessageService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        QqBotProperties properties = new QqBotProperties();
        properties.setEnabled(true);
        properties.setWebhookToken("secret");
        properties.setApiBaseUrl("http://127.0.0.1:3000");
        properties.setAllowedGroups(java.util.List.of("222"));
        properties.setAllowedUsers(java.util.List.of("999"));

        client = mock(OneBotClient.class);
        tools = mock(MonitorChatTools.class);
        when(tools.platformOverview()).thenReturn("平台概览内容");

        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        service = new BotMessageService(properties, client, tools, provider);
    }

    private OneBotEvent event(String json) throws Exception {
        return mapper.readValue(json, OneBotEvent.class);
    }

    @Test
    void shouldReplyToPrivateCommand() throws Exception {
        service.onEvent(event("""
                {"post_type":"message","message_type":"private","user_id":999,
                 "self_id":1,"raw_message":"/平台","message":[{"type":"text","data":{"text":"/平台"}}]}
                """));

        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), anyString());
    }

    @Test
    void shouldIgnoreNonWhitelistedGroup() throws Exception {
        service.onEvent(event("""
                {"post_type":"message","message_type":"group","group_id":111,"user_id":5,
                 "self_id":1,"raw_message":"[CQ:at,qq=1] /平台",
                 "message":[{"type":"at","data":{"qq":"1"}},{"type":"text","data":{"text":" /平台"}}]}
                """));

        verify(client, never()).sendGroupMessage(anyLong(), anyString());
    }

    @Test
    void shouldIgnoreGroupMessageWithoutMention() throws Exception {
        service.onEvent(event("""
                {"post_type":"message","message_type":"group","group_id":222,"user_id":5,
                 "self_id":1,"raw_message":"/平台",
                 "message":[{"type":"text","data":{"text":"/平台"}}]}
                """));

        verify(client, never()).sendGroupMessage(anyLong(), anyString());
    }

    @Test
    void shouldReplyToMentionedGroupCommand() throws Exception {
        service.onEvent(event("""
                {"post_type":"message","message_type":"group","group_id":222,"user_id":5,
                 "self_id":1,"raw_message":"[CQ:at,qq=1] /平台",
                 "message":[{"type":"at","data":{"qq":"1"}},{"type":"text","data":{"text":" /平台"}}]}
                """));

        verify(client, timeout(3000)).sendGroupMessage(eq(222L), anyString());
        verify(tools, timeout(3000)).platformOverview();
    }

    @Test
    void shouldIgnoreNonMessageEvents() throws Exception {
        service.onEvent(event("""
                {"post_type":"meta_event","meta_event_type":"heartbeat","self_id":1}
                """));

        verify(client, never()).sendPrivateMessage(anyLong(), anyString());
        verify(client, never()).sendGroupMessage(anyLong(), anyString());
    }
}