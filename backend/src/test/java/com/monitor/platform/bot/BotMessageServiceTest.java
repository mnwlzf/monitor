package com.monitor.platform.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.bot.identity.BotIdentity;
import com.monitor.platform.bot.identity.BotIdentityResolver;
import com.monitor.platform.bot.identity.QqUserBindingService;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.bot.onebot.OneBotEvent;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.bot.report.BotReportRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 机器人消息路由：白名单、@ 判断、身份分流与命令权限。
 *
 * <p>这里刻意不提供 {@link ChatClient.Builder}，验证「没配大模型也能用命令」。</p>
 */
class BotMessageServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private OneBotClient client;
    private MonitorChatTools tools;
    private BotIdentityResolver identityResolver;
    private QqUserBindingService bindings;
    private BotReportRenderer reportRenderer;
    private BotMessageService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        BotSettingsService settingsService = mock(BotSettingsService.class);
        when(settingsService.current()).thenReturn(new BotSettings(
                true, java.util.Set.of("222"), java.util.Set.of("999"), true, "/", 900, 10));

        client = mock(OneBotClient.class);
        tools = mock(MonitorChatTools.class);
        when(tools.platformReport()).thenReturn(BotReport.fromMarkdown("平台概览内容"));

        identityResolver = mock(BotIdentityResolver.class);
        bindings = mock(QqUserBindingService.class);
        reportRenderer = mock(BotReportRenderer.class);

        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        service = new BotMessageService(
                settingsService, client, tools, identityResolver, bindings, reportRenderer, provider);
    }

    private OneBotEvent event(String json) throws Exception {
        return mapper.readValue(json, OneBotEvent.class);
    }

    private void identityIs(BotIdentity identity) {
        when(identityResolver.resolve(anyLong())).thenReturn(identity);
    }

    private static BotIdentity admin() {
        return new BotIdentity(true, true, "admin@qq.com");
    }

    private static BotIdentity normalUser() {
        return new BotIdentity(true, false, "2755457558@qq.com");
    }

    private static String privateCommand(String text) {
        return """
                {"post_type":"message","message_type":"private","user_id":999,
                 "self_id":1,"raw_message":"%s","message":[{"type":"text","data":{"text":"%s"}}]}
                """.formatted(text, text);
    }

    /** 造一份纯文本必然超过 maxReplyLength(900) 的报表。 */
    private static BotReport longReport() {
        List<BotReport.Row> rows = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            rows.add(BotReport.Row.of("账号" + i, "12.34", "56.78", "正常"));
        }
        BotReport.Block table = new BotReport.Block(null, List.of(
                BotReport.Col.left("账号"),
                BotReport.Col.right("余额"),
                BotReport.Col.right("已用额度"),
                BotReport.Col.left("状态")), rows, null);
        return BotReport.of("平台概览", List.of(table), List.of());
    }

    @Test
    void shouldReplyToPrivateCommand() throws Exception {
        identityIs(admin());

        service.onEvent(event(privateCommand("/平台")));

        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), anyString());
    }

    @Test
    void shouldIgnoreNonWhitelistedGroup() throws Exception {
        identityIs(admin());

        service.onEvent(event("""
                {"post_type":"message","message_type":"group","group_id":111,"user_id":5,
                 "self_id":1,"raw_message":"[CQ:at,qq=1] /平台",
                 "message":[{"type":"at","data":{"qq":"1"}},{"type":"text","data":{"text":" /平台"}}]}
                """));

        verify(client, never()).sendGroupMessage(anyLong(), anyString());
    }

    @Test
    void shouldIgnoreGroupMessageWithoutMention() throws Exception {
        identityIs(admin());

        service.onEvent(event("""
                {"post_type":"message","message_type":"group","group_id":222,"user_id":5,
                 "self_id":1,"raw_message":"/平台",
                 "message":[{"type":"text","data":{"text":"/平台"}}]}
                """));

        verify(client, never()).sendGroupMessage(anyLong(), anyString());
    }

    @Test
    void shouldReplyToMentionedGroupCommandForAdmin() throws Exception {
        identityIs(admin());

        service.onEvent(event("""
                {"post_type":"message","message_type":"group","group_id":222,"user_id":5,
                 "self_id":1,"raw_message":"[CQ:at,qq=1] /平台",
                 "message":[{"type":"at","data":{"qq":"1"}},{"type":"text","data":{"text":" /平台"}}]}
                """));

        verify(client, timeout(3000)).sendGroupMessage(eq(222L), anyString());
        verify(tools, timeout(3000)).platformReport();
    }

    @Test
    void shouldIgnoreNonMessageEvents() throws Exception {
        service.onEvent(event("""
                {"post_type":"meta_event","meta_event_type":"heartbeat","self_id":1}
                """));

        verify(client, never()).sendPrivateMessage(anyLong(), anyString());
        verify(client, never()).sendGroupMessage(anyLong(), anyString());
    }

    /** 普通用户问平台数据：明确拒绝，且绝不能真的去查。 */
    @Test
    void shouldDenyPlatformCommandForNormalUser() throws Exception {
        identityIs(normalUser());

        service.onEvent(event(privateCommand("/平台")));

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), reply.capture());
        assertEquals("平台级数据仅管理员可查。", reply.getValue());
        verifyNoInteractions(tools);
    }

    /** 陌生人发平台命令：必须看起来「没有这个功能」，不能暴露平台的存在。 */
    @Test
    void shouldHidePlatformCommandFromGuest() throws Exception {
        identityIs(BotIdentity.guest());

        service.onEvent(event(privateCommand("/平台")));

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), reply.capture());
        assertEquals("这个命令我用不了，发送 /help 看看能做什么。", reply.getValue());
        verifyNoInteractions(tools);
    }

    /** 陌生人的 /help 不能出现任何平台功能。 */
    @Test
    void shouldShowGuestHelpWithoutPlatformCommands() throws Exception {
        identityIs(BotIdentity.guest());

        service.onEvent(event(privateCommand("/help")));

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), reply.capture());
        String text = reply.getValue();
        assertFalse(text.contains("号池"), "陌生人不应看到号池命令：" + text);
        assertFalse(text.contains("平台"), "陌生人不应看到平台命令：" + text);
        assertFalse(text.contains("账号"), "陌生人不应看到账号命令：" + text);
        assertFalse(text.contains("变更"), "陌生人不应看到变更命令：" + text);
    }

    /** 绑定回复必须与邮箱是否命中平台用户无关，否则可被用来枚举平台用户。 */
    @Test
    void shouldReplyNeutrallyToBind() throws Exception {
        identityIs(BotIdentity.guest());
        when(bindings.bind(anyLong(), anyString())).thenReturn(true);

        service.onEvent(event(privateCommand("/绑定 someone@example.com")));

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), reply.capture());
        assertEquals("已记录你的邮箱。", reply.getValue());
        verify(bindings).bind(999L, "someone@example.com");
    }

    @Test
    void shouldRejectMalformedEmailOnBind() throws Exception {
        identityIs(BotIdentity.guest());

        service.onEvent(event(privateCommand("/绑定 not-an-email")));

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), reply.capture());
        assertEquals("邮箱格式不正确。用法：/绑定 <邮箱>", reply.getValue());
        verify(bindings, never()).bind(anyLong(), anyString());
    }

    /** 超过 maxReplyLength 的回复应渲染成图片发送，而不是被截断。 */
    @Test
    void shouldSendLongReplyAsImage() throws Exception {
        identityIs(admin());
        when(tools.platformReport()).thenReturn(longReport());
        when(reportRenderer.render(any(BotReport.class))).thenReturn(new byte[]{1, 2, 3});
        when(client.sendPrivateImage(eq(999L), any(byte[].class))).thenReturn(true);

        service.onEvent(event(privateCommand("/平台")));

        verify(client, timeout(3000)).sendPrivateImage(eq(999L), any(byte[].class));
        verify(client, never()).sendPrivateMessage(anyLong(), anyString());
    }

    /** 渲染不出图片（例如字体不可用）时，退回截断文本。 */
    @Test
    void shouldFallBackToTruncatedTextWhenRenderUnavailable() throws Exception {
        identityIs(admin());
        when(tools.platformReport()).thenReturn(longReport());
        when(reportRenderer.render(any(BotReport.class))).thenReturn(null);

        service.onEvent(event(privateCommand("/平台")));

        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), contains("已截断"));
    }

    /** 图片发送失败也必须退回截断文本 —— 不能什么都不发。 */
    @Test
    void shouldFallBackToTruncatedTextWhenImageSendFails() throws Exception {
        identityIs(admin());
        when(tools.platformReport()).thenReturn(longReport());
        when(reportRenderer.render(any(BotReport.class))).thenReturn(new byte[]{1});
        when(client.sendPrivateImage(eq(999L), any(byte[].class))).thenReturn(false);

        service.onEvent(event(privateCommand("/平台")));

        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), contains("已截断"));
    }

    /** 短回复不应触发图片渲染，避免每条消息都生成图片。 */
    @Test
    void shouldSendShortReplyAsTextWithoutRendering() throws Exception {
        identityIs(admin());

        service.onEvent(event(privateCommand("/平台")));

        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), anyString());
        verifyNoInteractions(reportRenderer);
    }
}