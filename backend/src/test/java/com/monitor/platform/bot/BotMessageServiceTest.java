package com.monitor.platform.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.bot.identity.BotIdentity;
import com.monitor.platform.bot.identity.BotIdentityResolver;
import com.monitor.platform.bot.identity.QqUserBindingService;
import com.monitor.platform.bot.identity.Sub2ApiUserCache;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.bot.user.BotUserService;
import com.monitor.platform.bot.onebot.OneBotEvent;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.bot.report.BotReportRenderer;
import com.monitor.platform.bot.archive.BotMessageArchiveService;
import com.monitor.platform.bot.vision.BotImageFetcher;
import com.monitor.platform.bot.vision.BotVisionProperties;
import com.monitor.platform.bot.weather.WeatherTools;
import com.monitor.platform.bot.order.BotOrderTools;
import com.monitor.platform.bot.usage.BotUsageTools;
import com.monitor.platform.bot.channel.BotChannelStatusService;
import com.monitor.platform.bot.image.ImageGenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    private BotOrderTools orderTools;
    private BotUsageTools usageTools;
    private BotChannelStatusService channelStatusService;
    private ImageGenService imageGenService;
    private BotRuntimeStatus runtimeStatus;
    private Sub2ApiUserCache userCache;
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
        orderTools = mock(BotOrderTools.class);
        usageTools = mock(BotUsageTools.class);
        channelStatusService = mock(BotChannelStatusService.class);
        imageGenService = mock(ImageGenService.class);
        runtimeStatus = new BotRuntimeStatus();
        userCache = mock(Sub2ApiUserCache.class);
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

        BotChatClientFactory chatClients = new BotChatClientFactory(tools, orderTools, usageTools, weatherTools, provider);
        BotCommandRouter commandRouter = new BotCommandRouter(
                tools, orderTools, usageTools, channelStatusService, botUserService, weatherTools, bindings, userCache, imageGenService);
        service = new BotMessageService(
                settingsService, client, new BotMessageParser(client), tools, channelStatusService, imageGenService, runtimeStatus, botUserService, archive, imageFetcher, visionProperties, identityResolver, reportRenderer, chatClients, commandRouter);
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
    /** 群临时会话，可指定发送者 QQ —— 用来验证「来源群 + QQ」双重判定。 */
    private static String tempSessionCommandFrom(Long groupId, long userId, String text) {
        return """
                {"post_type":"message","message_type":"private","sub_type":"group","group_id":%d,"user_id":%d,
                 "self_id":1,"raw_message":"%s","message":[{"type":"text","data":{"text":"%s"}}]}
                """.formatted(groupId, userId, text, text);
    }    private static String tempSessionCommand(Long groupId, String text) {
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

    /** 群里没 @机器人：不算「未回复」—— 这条消息本来就不需要回。 */
    @Test
    void shouldNotCountGroupMessageWithoutMentionAsDropped() throws Exception {
        identityIs(admin());

        service.onEvent(event("""
                {"post_type":"message","message_type":"group","group_id":222,"user_id":999,
                 "self_id":1,"raw_message":"/平台",
                 "message":[{"type":"text","data":{"text":"/平台"}}]}
                """));

        verify(client, never()).sendGroupMessage(anyLong(), anyString());
        assertEquals(0L, runtimeStatus.droppedCount());
    }    @Test
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

    // ============================================================ 管理端只读：消耗 / 异常 / 任务

    /** 管理员的 /消耗：走全平台用量排行，默认 10 条。 */
    @Test
    void shouldReturnUsageRankingForAdmin() throws Exception {
        identityIs(admin());
        when(tools.usageReport(10)).thenReturn(BotReport.fromMarkdown("账号用量排行内容"));

        service.onEvent(event(privateCommand("/消耗")));

        verify(tools, timeout(3000)).usageReport(10);
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), contains("账号用量排行内容"));
    }

    /** /消耗 带条数时必须按参数取数，而不是永远用默认值。 */
    @Test
    void shouldPassLimitToUsageRanking() throws Exception {
        identityIs(admin());
        when(tools.usageReport(3)).thenReturn(BotReport.fromMarkdown("账号用量排行内容"));

        service.onEvent(event(privateCommand("/消耗 3")));

        verify(tools, timeout(3000)).usageReport(3);
        verify(tools, never()).usageReport(10);
    }

    /** 管理员的 /异常：走异常账号名单。 */
    @Test
    void shouldReturnAbnormalAccountsForAdmin() throws Exception {
        identityIs(admin());
        when(tools.abnormalReport()).thenReturn(BotReport.fromMarkdown("异常账号内容"));

        service.onEvent(event(privateCommand("/异常")));

        verify(tools, timeout(3000)).abnormalReport();
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), contains("异常账号内容"));
    }

    /** 管理员的 /任务：走定时任务状态。 */
    @Test
    void shouldReturnScheduledTasksForAdmin() throws Exception {
        identityIs(admin());
        when(tools.taskReport()).thenReturn(BotReport.fromMarkdown("定时任务内容"));

        service.onEvent(event(privateCommand("/任务")));

        verify(tools, timeout(3000)).taskReport();
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), contains("定时任务内容"));
    }

    /** 陌生人问 /异常：按未知命令处理，绝不暴露平台功能，也不去查。 */
    @Test
    void shouldHideAbnormalCommandFromGuest() throws Exception {
        identityIs(BotIdentity.guest());

        service.onEvent(event(privateCommand("/异常")));

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), reply.capture());
        assertEquals("这个命令我用不了，发送 /help 看看能做什么。", reply.getValue());
        verifyNoInteractions(tools);
    }

    // ============================================================ 管理端只读：订单 / 用户余额

    /** 管理员的 /订单：不写日期时走默认时间窗。 */
    @Test
    void shouldReturnOrdersForAdminWithDefaultRange() throws Exception {
        identityIs(admin());
        when(orderTools.ordersReport(eq("user@example.com"), eq(""), eq(""), anyInt()))
                .thenReturn(BotReport.fromMarkdown("订单内容"));

        service.onEvent(event(privateCommand("/订单 user@example.com")));

        verify(orderTools, timeout(3000)).ordersReport("user@example.com", "", "", BotOrderTools.DEFAULT_LIMIT);
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), contains("订单内容"));
    }

    /** 日期范围必须原样透传到取数层。 */
    @Test
    void shouldPassDateRangeToOrders() throws Exception {
        identityIs(admin());
        when(orderTools.ordersReport(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(BotReport.fromMarkdown("订单内容"));

        service.onEvent(event(privateCommand("/订单 user@example.com 2026-09-01 2026-09-30")));

        verify(orderTools, timeout(3000))
                .ordersReport("user@example.com", "2026-09-01", "2026-09-30", BotOrderTools.DEFAULT_LIMIT);
    }

    /** 只给一个日期时，它作为起始日期，结束日期交给取数层按今天算。 */
    @Test
    void shouldTreatSingleDateAsStart() throws Exception {
        identityIs(admin());
        when(orderTools.ordersReport(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(BotReport.fromMarkdown("订单内容"));

        service.onEvent(event(privateCommand("/订单 user@example.com 2026-09-01")));

        verify(orderTools, timeout(3000))
                .ordersReport("user@example.com", "2026-09-01", "", BotOrderTools.DEFAULT_LIMIT);
    }

    /** /订单 不带账号时只回用法，绝不去查。 */
    @Test
    void shouldPrintUsageWhenOrderAccountMissing() throws Exception {
        identityIs(admin());

        service.onEvent(event(privateCommand("/订单")));

        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), contains("用法"));
        verifyNoInteractions(orderTools);
    }

    /** 管理员的 /用户余额：走指定平台用户的余额查询。 */
    @Test
    void shouldReturnUserBalanceForAdmin() throws Exception {
        identityIs(admin());
        when(orderTools.userBalanceReport("user@example.com")).thenReturn(BotReport.fromMarkdown("余额内容"));

        service.onEvent(event(privateCommand("/用户余额 user@example.com")));

        verify(orderTools, timeout(3000)).userBalanceReport("user@example.com");
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), contains("余额内容"));
    }

    /** 陌生人问 /订单：按未知命令处理，绝不暴露平台功能，也不去查订单。 */
    @Test
    void shouldHideOrdersFromGuest() throws Exception {
        identityIs(BotIdentity.guest());

        service.onEvent(event(privateCommand("/订单 user@example.com")));

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), reply.capture());
        assertEquals("这个命令我用不了，发送 /help 看看能做什么。", reply.getValue());
        verifyNoInteractions(orderTools);
    }

    // ============================================================ 管理端只读：所有用户的余额消耗

    /** 管理员的 /用户消耗：不带日期时把空串交给取数层，由它按「今天」处理。 */
    @Test
    void shouldReturnUserConsumptionForAdmin() throws Exception {
        identityIs(admin());
        when(usageTools.userConsumptionReport("")).thenReturn(BotReport.fromMarkdown("消耗内容"));

        service.onEvent(event(privateCommand("/用户消耗")));

        verify(usageTools, timeout(3000)).userConsumptionReport("");
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), contains("消耗内容"));
    }

    /** 指定日期必须原样透传到取数层。 */
    @Test
    void shouldPassDateToUserConsumption() throws Exception {
        identityIs(admin());
        when(usageTools.userConsumptionReport("2026-10-10")).thenReturn(BotReport.fromMarkdown("消耗内容"));

        service.onEvent(event(privateCommand("/用户消耗 2026-10-10")));

        verify(usageTools, timeout(3000)).userConsumptionReport("2026-10-10");
    }

    /** 陌生人问 /用户消耗：按未知命令处理，绝不暴露平台功能，也不去查。 */
    @Test
    void shouldHideUserConsumptionFromGuest() throws Exception {
        identityIs(BotIdentity.guest());

        service.onEvent(event(privateCommand("/用户消耗 2026-10-10")));

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), reply.capture());
        assertEquals("这个命令我用不了，发送 /help 看看能做什么。", reply.getValue());
        verifyNoInteractions(usageTools);
    }    // ============================================================ 渠道状态：管理员与平台用户都能看

    /** 管理员的 /渠道状态：不过滤分组，viewerUserId 传 null。 */
    @Test
    void shouldReturnChannelStatusForAdmin() throws Exception {
        identityIs(admin());
        when(channelStatusService.report(true, null, "")).thenReturn(BotReport.fromMarkdown("渠道状态内容"));

        service.onEvent(event(privateCommand("/渠道状态")));

        verify(channelStatusService, timeout(3000)).report(true, null, "");
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), contains("渠道状态内容"));
    }

    /** 平台用户在指定群用 /渠道状态：按自己的 Sub2API 用户 ID 限定可见分组。 */
    @Test
    void shouldScopeChannelStatusToViewerGroups() throws Exception {
        identityIs(normalUser());
        when(userCache.userId("2755457558@qq.com")).thenReturn(java.util.Optional.of(42L));
        when(channelStatusService.report(false, 42L, "")).thenReturn(BotReport.fromMarkdown("渠道状态内容"));

        service.onEvent(event(mentionedGroupCommand(222L, "/渠道状态")));

        verify(channelStatusService, timeout(3000)).report(false, 42L, "");
        verify(client, timeout(3000)).sendGroupMessage(eq(222L), contains("渠道状态内容"));
    }

    /** 陌生人问 /渠道状态：按未知命令处理，既不暴露功能也不去查。 */
    @Test
    void shouldHideChannelStatusFromGuest() throws Exception {
        identityIs(BotIdentity.guest());

        service.onEvent(event(privateCommand("/渠道状态")));

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), reply.capture());
        assertEquals("这个命令我用不了，发送 /help 看看能做什么。", reply.getValue());
        verifyNoInteractions(channelStatusService);
    }    // ============================================================ 会话记忆隔离

    /**
     * 同一个群里，不同的人绝不能共用一段记忆。
     *
     * <p>线上踩过：群聊作用域只带 groupId，A 说过的话进了 B 的上下文，
     * 看起来就是「把别人的聊天内容加到了另外的人身上」。</p>
     */
    @Test
    void shouldIsolateMemoryPerUserInGroup() {
        assertNotEquals(
                BotMessageService.memoryScope(BotChannel.GROUP, 222L, 10001L),
                BotMessageService.memoryScope(BotChannel.GROUP, 222L, 10002L));
    }

    /** 同一个人：换群、私聊、临时会话都要各自独立，避免上下文串台。 */
    @Test
    void shouldIsolateMemoryByChannel() {
        String groupA = BotMessageService.memoryScope(BotChannel.GROUP, 222L, 10001L);
        String groupB = BotMessageService.memoryScope(BotChannel.GROUP, 333L, 10001L);
        String privateChat = BotMessageService.memoryScope(BotChannel.PRIVATE, null, 10001L);
        String tempSession = BotMessageService.memoryScope(BotChannel.TEMP_SESSION, 222L, 10001L);

        assertNotEquals(groupA, groupB);
        assertNotEquals(groupA, privateChat);
        assertNotEquals(privateChat, tempSession);
        assertNotEquals(groupA, tempSession);
    }

    // ============================================================ 兜底：任何异常都要有回复

    /**
     * 处理链路里任何未捕获的异常都必须回一句。
     *
     * <p>线上踩过：{@code executor.submit()} 返回的 Future 没人接收，任务里抛出的异常会被
     * 静默吞掉 —— 没有日志、没有回复、连消息存档都没有，表现就是「调用失败但用户什么都没收到」。
     * 这里让身份解析抛异常，验证兜底会回一句。</p>
     */
    @Test
    void shouldReplyWhenHandlerThrowsUnexpectedly() throws Exception {
        when(identityResolver.resolve(anyLong())).thenThrow(new IllegalStateException("redis down"));

        service.onEvent(event(privateCommand("/help")));

        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), contains("出错了"));
    }

    // ============================================================ 生图：所有人都能用

    /** 路人也应该能 /画图：生成成功后把图片发出去，再跟一句文字。 */
    @Test
    void shouldAllowDrawForGuest() throws Exception {
        identityIs(BotIdentity.guest());
        when(imageGenService.generate(eq("一只猫"), any(Runnable.class))).thenReturn(
                new ImageGenService.Result(new byte[]{1, 2, 3}, "a cat", null));
        when(client.sendPrivateImage(eq(999L), any(byte[].class))).thenReturn(true);

        service.onEvent(event(privateCommand("/画图 一只猫")));

        verify(imageGenService, timeout(3000)).generate(eq("一只猫"), any(Runnable.class));
        verify(client, timeout(3000)).sendPrivateImage(eq(999L), any(byte[].class));
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), contains("画好了"));
    }

    /** 开始画之前要先回一句，避免用户以为机器人没反应（生图要 20 秒以上）。 */
    @Test
    void shouldNotifyUserBeforeGenerating() throws Exception {
        identityIs(BotIdentity.guest());
        // 模拟「通过限频、真的开始画」：服务在限频检查之后回调
        when(imageGenService.generate(eq("一只猫"), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return new ImageGenService.Result(new byte[]{1, 2, 3}, "a cat", null);
        });
        when(client.sendPrivateImage(eq(999L), any(byte[].class))).thenReturn(true);

        service.onEvent(event(privateCommand("/画图 一只猫")));

        ArgumentCaptor<String> replies = ArgumentCaptor.forClass(String.class);
        verify(client, timeout(3000).atLeastOnce()).sendPrivateMessage(eq(999L), replies.capture());
        assertTrue(replies.getAllValues().stream().anyMatch(text -> text.contains("正在画")),
                "生图开始前应当先回一句「正在画」，实际收到的回复：" + replies.getAllValues());
    }    /** 生图失败（例如被限频或上游报错）时，把原因原样回给用户，且不发图。 */
    @Test
    void shouldReplyWithReasonWhenDrawFails() throws Exception {
        identityIs(BotIdentity.guest());
        when(imageGenService.generate(anyString(), any(Runnable.class))).thenReturn(
                new ImageGenService.Result(null, null, "生图太频繁了，一分钟最多 3 张，约 30 秒后再试。"));

        service.onEvent(event(privateCommand("/画图 一只猫")));

        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), contains("太频繁"));
        verify(client, never()).sendPrivateImage(anyLong(), any(byte[].class));
    }

    // ============================================================ 群临时会话：来源群 + QQ 双重判定

    /**
     * 临时会话的<strong>唯一限制是来源群</strong>：只要发起群在「允许的群号」里就放行，
     * <strong>不看</strong>发送者是否在私聊白名单里。
     *
     * <p>线上问题：用户 1981329205 在启用群 1097845610 里发起临时会话，因为不在
     * 「允许的私聊 QQ」里被静默丢弃，而同一个人在群里 @机器人 却正常 —— 规则自相矛盾。
     * 临时会话本质是「群成员跟机器人说话」，权限应当继承自群。</p>
     */
    @Test
    void shouldAllowTempSessionBySourceGroupOnly() throws Exception {
        identityIs(BotIdentity.guest());

        service.onEvent(event(tempSessionCommandFrom(222L, 888L, "/help")));

        verify(client, timeout(3000)).sendPrivateMessage(eq(888L), contains("我能做的事"));
    }

    /** 来源群与发送者都在白名单里时，当然也能用。 */
    @Test
    void shouldAllowTempSessionWhenGroupAndUserBothAllowed() throws Exception {
        identityIs(BotIdentity.guest());

        service.onEvent(event(tempSessionCommandFrom(222L, 999L, "/help")));

        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), contains("我能做的事"));
    }

    /** 来源群没启用 —— 不回复（唯一限制就是来源群）。发送者在不在私聊白名单都不影响。 */
    @Test
    void shouldRejectTempSessionWhenSourceGroupNotAllowed() throws Exception {
        identityIs(BotIdentity.guest());

        service.onEvent(event(tempSessionCommandFrom(777L, 999L, "/help")));

        verify(client, never()).sendPrivateMessage(anyLong(), anyString());
    }    // ============================================================ 用户端：只能查自己

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