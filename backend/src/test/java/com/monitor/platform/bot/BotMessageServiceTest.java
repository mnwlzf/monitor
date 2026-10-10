package com.monitor.platform.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.bot.identity.BotIdentity;
import com.monitor.platform.bot.identity.BotIdentityResolver;
import com.monitor.platform.bot.identity.QqUserBindingService;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.bot.user.BotUserService;
import com.monitor.platform.bot.onebot.OneBotEvent;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.bot.report.BotReportRenderer;
import com.monitor.platform.bot.archive.BotMessageArchiveService;
import com.monitor.platform.bot.vision.BotImageFetcher;
import com.monitor.platform.bot.vision.BotVisionProperties;
import com.monitor.platform.bot.weather.WeatherTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private BotUserService botUserService;
    private BotIdentityResolver identityResolver;
    private QqUserBindingService bindings;
    private BotReportRenderer reportRenderer;
    private WeatherTools weatherTools;
    private BotMessageArchiveService archive;
    private BotImageFetcher imageFetcher;
    private BotVisionProperties visionProperties;
    private BotMessageService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        BotSettingsService settingsService = mock(BotSettingsService.class);
        when(settingsService.current()).thenReturn(new BotSettings(
                true, java.util.Set.of("222", "333"), java.util.Set.of("999"), java.util.Set.of("222"),
                true, "/", 900, 10));

        client = mock(OneBotClient.class);
        tools = mock(MonitorChatTools.class);
        botUserService = mock(BotUserService.class);
        when(tools.platformReport()).thenReturn(BotReport.fromMarkdown("平台概览内容"));

        identityResolver = mock(BotIdentityResolver.class);
        bindings = mock(QqUserBindingService.class);
        reportRenderer = mock(BotReportRenderer.class);
        weatherTools = mock(WeatherTools.class);
        archive = mock(BotMessageArchiveService.class);
        imageFetcher = mock(BotImageFetcher.class);
        visionProperties = new BotVisionProperties();

        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        service = new BotMessageService(
                settingsService, client, tools, botUserService, weatherTools, archive, imageFetcher, visionProperties, identityResolver, bindings, reportRenderer, provider);
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

    /** 群里 @机器人 后发一条命令。 */
    private static String mentionedGroupCommand(Long groupId, String text) {
        return """
                {"post_type":"message","message_type":"group","group_id":%d,"user_id":999,
                 "self_id":1,"raw_message":"[CQ:at,qq=1] %s",
                 "message":[{"type":"at","data":{"qq":"1"}},{"type":"text","data":{"text":" %s"}}]}
                """.formatted(groupId, text, text);
    }

    /** 群临时会话：private + sub_type=group，group_id 是发起群。 */
    private static String tempSessionCommand(Long groupId, String text) {
        return """
                {"post_type":"message","message_type":"private","sub_type":"group","group_id":%d,"user_id":999,
                 "self_id":1,"raw_message":"%s","message":[{"type":"text","data":{"text":"%s"}}]}
                """.formatted(groupId, text, text);
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

    /** 普通用户在指定群里问平台数据：明确拒绝，且绝不能真的去查。 */
    @Test
    void shouldDenyPlatformCommandForNormalUserInDesignatedGroup() throws Exception {
        identityIs(normalUser());

        service.onEvent(event(mentionedGroupCommand(222L, "/平台")));

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(3000)).sendGroupMessage(eq(222L), reply.capture());
        assertEquals("平台级数据仅管理员可查。", reply.getValue());
        verifyNoInteractions(tools);
    }

    /** 普通用户好友私聊：不给平台功能，按「未知命令」处理（不暴露平台）。 */
    @Test
    void shouldHidePlatformFromNormalUserInFriendPrivate() throws Exception {
        identityIs(normalUser());

        service.onEvent(event(privateCommand("/平台")));

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), reply.capture());
        assertEquals("这个命令我用不了，发送 /help 看看能做什么。", reply.getValue());
        verifyNoInteractions(tools);
    }

    /** 普通用户从「指定群」发起临时会话：命中双重匹配，平台功能可用（但仍只到用户级）。 */
    @Test
    void shouldDenyPlatformForNormalUserInTempSessionOfDesignatedGroup() throws Exception {
        identityIs(normalUser());

        service.onEvent(event(tempSessionCommand(222L, "/平台")));

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), reply.capture());
        assertEquals("平台级数据仅管理员可查。", reply.getValue());
        verifyNoInteractions(tools);
    }

    /** 普通用户从「非指定群」发起临时会话：不命中群匹配，按未知命令处理。 */
    @Test
    void shouldHidePlatformForNormalUserInTempSessionOfNonDesignatedGroup() throws Exception {
        identityIs(normalUser());

        service.onEvent(event(tempSessionCommand(333L, "/平台")));

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), reply.capture());
        assertEquals("这个命令我用不了，发送 /help 看看能做什么。", reply.getValue());
        verifyNoInteractions(tools);
    }

    /** 管理员在「启用群但非指定群」里：不给平台功能。 */
    @Test
    void shouldDenyPlatformForAdminInNonDesignatedGroup() throws Exception {
        identityIs(admin());

        service.onEvent(event(mentionedGroupCommand(333L, "/平台")));

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(3000)).sendGroupMessage(eq(333L), reply.capture());
        assertEquals("这个命令我用不了，发送 /help 看看能做什么。", reply.getValue());
        verifyNoInteractions(tools);
    }

    /** 管理员在指定群里：平台功能可用。 */
    @Test
    void shouldAllowPlatformForAdminInDesignatedGroup() throws Exception {
        identityIs(admin());

        service.onEvent(event(mentionedGroupCommand(222L, "/平台")));

        verify(tools, timeout(3000)).platformReport();
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
        assertTrue(text.contains("天气"), "天气对所有人开放，陌生人应能看到：" + text);
    }

    /** 天气与平台无关，陌生人也能查。 */
    @Test
    void shouldAllowWeatherForGuest() throws Exception {
        identityIs(BotIdentity.guest());
        when(weatherTools.weatherReport(anyString(), anyInt()))
                .thenReturn(BotReport.fromMarkdown("北京 · 实时天气：晴 26°C"));

        service.onEvent(event(privateCommand("/天气 北京")));

        verify(weatherTools, timeout(3000)).weatherReport(eq("北京"), anyInt());
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), anyString());
    }

    /** 普通用户同样能查天气（不能因为不是管理员就被拦掉）。 */
    @Test
    void shouldAllowWeatherForNormalUser() throws Exception {
        identityIs(normalUser());
        when(weatherTools.weatherReport(anyString(), anyInt()))
                .thenReturn(BotReport.fromMarkdown("北京 · 实时天气：晴 26°C"));

        service.onEvent(event(privateCommand("/天气 北京")));

        verify(weatherTools, timeout(3000)).weatherReport(eq("北京"), anyInt());
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

    // ============================================================ 用户端：只能查自己

    /** 平台用户在指定群里查自己的余额：放行，且用的是发送者自己的邮箱。 */
    @Test
    void shouldReturnMyBalanceInDesignatedGroup() throws Exception {
        identityIs(normalUser());
        when(botUserService.balanceReport(anyString())).thenReturn(BotReport.fromMarkdown("余额 12.34"));

        service.onEvent(event(mentionedGroupCommand(222L, "/我的余额")));

        verify(botUserService, timeout(3000)).balanceReport("2755457558@qq.com");
        verify(client, timeout(3000)).sendGroupMessage(eq(222L), contains("余额 12.34"));
    }

    /** 平台用户在好友私聊查自己的数据：不给平台功能，按未知命令处理。 */
    @Test
    void shouldHideMyDataInFriendPrivate() throws Exception {
        identityIs(normalUser());

        service.onEvent(event(privateCommand("/我的余额")));

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), reply.capture());
        assertEquals("这个命令我用不了，发送 /help 看看能做什么。", reply.getValue());
        verifyNoInteractions(botUserService);
    }

    /** 平台用户从非指定群的临时会话查自己：同样不给。 */
    @Test
    void shouldHideMyDataInTempSessionOfNonDesignatedGroup() throws Exception {
        identityIs(normalUser());

        service.onEvent(event(tempSessionCommand(333L, "/我的密钥")));

        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), anyString());
        verifyNoInteractions(botUserService);
    }

    /** 用量命令把时间窗透传下去。 */
    @Test
    void shouldPassUsageRangeThrough() throws Exception {
        identityIs(normalUser());
        when(botUserService.usageReport(anyString(), anyString())).thenReturn(BotReport.fromMarkdown("请求数 10"));

        service.onEvent(event(mentionedGroupCommand(222L, "/我的用量 7d")));

        verify(botUserService, timeout(3000)).usageReport("2755457558@qq.com", "7d");
    }

    /** 密钥命令。 */
    @Test
    void shouldReturnMyApiKeysInDesignatedGroup() throws Exception {
        identityIs(normalUser());
        when(botUserService.apiKeysReport(anyString())).thenReturn(BotReport.fromMarkdown("密钥 A"));

        service.onEvent(event(mentionedGroupCommand(222L, "/我的密钥")));

        verify(botUserService, timeout(3000)).apiKeysReport("2755457558@qq.com");
    }

    /** 陌生人即使在指定群里问「我的余额」，也不能触发用户端查询。 */
    @Test
    void shouldHideMyDataFromGuest() throws Exception {
        identityIs(BotIdentity.guest());

        service.onEvent(event(mentionedGroupCommand(222L, "/我的余额")));

        verify(client, timeout(3000)).sendGroupMessage(eq(222L), anyString());
        verifyNoInteractions(botUserService);
    }

    /** 管理员在私聊查自己的余额：管理员私聊放行。 */
    @Test
    void shouldReturnMyBalanceForAdminInPrivate() throws Exception {
        identityIs(admin());
        when(botUserService.balanceReport(anyString())).thenReturn(BotReport.fromMarkdown("余额 1.00"));

        service.onEvent(event(privateCommand("/我的余额")));

        verify(botUserService, timeout(3000)).balanceReport("admin@qq.com");
    }
}