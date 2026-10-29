package com.monitor.platform.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.bot.identity.BotIdentity;
import com.monitor.platform.bot.identity.BotIdentityResolver;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.bot.user.BotBalanceService;
import com.monitor.platform.bot.user.BotKeyUsageService;
import com.monitor.platform.bot.user.BotUserService;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.ops.OpsIncidentService;
import com.monitor.platform.bot.report.BotGroupRedactor;
import com.monitor.platform.bot.report.BotReportRenderer;
import com.monitor.platform.bot.archive.BotMessageArchiveService;
import com.monitor.platform.bot.vision.BotImageFetcher;
import com.monitor.platform.bot.vision.BotVisionProperties;
import com.monitor.platform.bot.voice.BotSpeechService;
import com.monitor.platform.bot.key.Sub2KeyTools;
import com.monitor.platform.bot.search.WebSearchProperties;
import com.monitor.platform.bot.search.WebSearchTools;
import com.monitor.platform.bot.weather.WeatherTools;
import com.monitor.platform.status.OpenAiStatusService;
import com.monitor.platform.bot.order.BotOrderTools;
import com.monitor.platform.bot.usage.BotUsageTools;
import com.monitor.platform.bot.channel.BotChannelStatusService;
import com.monitor.platform.bot.error.BotErrorQueryAuditService;
import com.monitor.platform.bot.error.BotErrorQueryGuard;
import com.monitor.platform.bot.error.BotErrorService;
import com.monitor.platform.bot.error.BotInstanceErrorService;
import com.monitor.platform.bot.error.BotErrorTools;
import com.monitor.platform.bot.identity.Sub2ApiUserCache;
import com.monitor.platform.bot.image.ImageGenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端：真实 OneBot 事件 JSON → HTTP 接口 → 路由 → 回复。
 *
 * <p>这里用的是真实的 {@link BotMessageService} 与 {@link QqBotWebhookController}，
 * 只有 OneBot 客户端与查询工具是 mock，覆盖 JSON 绑定、鉴权、路由到出站调用的整条链路。</p>
 */
class QqBotWebhookIntegrationTest {

    private OneBotClient client;
    private MonitorChatTools tools;
    private MockMvc mockMvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        QqBotProperties properties = new QqBotProperties();
        properties.setEnabled(true);
        properties.setWebhookToken("secret");
        properties.setApiBaseUrl("http://127.0.0.1:3000");

        client = mock(OneBotClient.class);
        tools = mock(MonitorChatTools.class);
        when(tools.poolReport(anyString())).thenReturn(BotReport.fromMarkdown("号池监控（近 24h）：账号 A 请求 10，缓存率 80.0%"));

        BotSettingsService settingsService = mock(BotSettingsService.class);
        when(settingsService.current()).thenReturn(new BotSettings(
                true, java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), true, "/", 900, 10, false, false, 15, 5, 1.5, 30));

        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        // 身份识别在单元测试里用桩：这里验证的是 HTTP → 路由 → 出站调用的链路
        BotIdentityResolver identityResolver = mock(BotIdentityResolver.class);
        when(identityResolver.resolve(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new BotIdentity(true, true, "admin@qq.com"));
        // 这里的回复很短，不会走图片渲染；仅需满足构造依赖
        BotReportRenderer reportRenderer = mock(BotReportRenderer.class);
        WeatherTools weatherTools = mock(WeatherTools.class);
        BotMessageArchiveService archive = mock(BotMessageArchiveService.class);
        BotImageFetcher imageFetcher = mock(BotImageFetcher.class);
        BotVisionProperties visionProperties = new BotVisionProperties();
        BotSpeechService speechService = mock(BotSpeechService.class);

        BotChannelStatusService channelStatusService = mock(BotChannelStatusService.class);
        BotErrorService errorService = mock(BotErrorService.class);
        BotInstanceErrorService instanceErrorService = mock(BotInstanceErrorService.class);
        BotErrorQueryGuard errorQueryGuard = mock(BotErrorQueryGuard.class);
        BotErrorQueryAuditService errorQueryAuditService = mock(BotErrorQueryAuditService.class);
        org.mockito.Mockito.when(errorQueryGuard.allow(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        BotErrorTools errorTools = mock(BotErrorTools.class);
        BotOrderTools orderTools = mock(BotOrderTools.class);
        BotUsageTools usageTools = mock(BotUsageTools.class);
        Sub2ApiUserCache userCache = mock(Sub2ApiUserCache.class);
        BotUserService botUserService = mock(BotUserService.class);
        ImageGenService imageGenService = mock(ImageGenService.class);
        OpenAiStatusService openAiStatusService = mock(OpenAiStatusService.class);
        BotGroupRedactor groupRedactor = mock(BotGroupRedactor.class);
        BotForbiddenWordService forbiddenWordService = mock(BotForbiddenWordService.class);
        BotEmailBindingService emailBindingService = mock(BotEmailBindingService.class);
        BotLotteryService lotteryService = mock(BotLotteryService.class);
        OpsIncidentService incidentService = mock(OpsIncidentService.class);
        BotChatClientFactory chatClients = new BotChatClientFactory(tools, errorTools, orderTools, usageTools, weatherTools,
                mock(WebSearchTools.class), mock(Sub2KeyTools.class), new WebSearchProperties(), provider,
                new BotModelFallbackProperties());
        BotCommandRouter commandRouter = new BotCommandRouter(
                tools, orderTools, usageTools, channelStatusService, errorService, instanceErrorService, errorQueryGuard, errorQueryAuditService, botUserService, weatherTools, userCache, imageGenService, openAiStatusService, forbiddenWordService, emailBindingService, lotteryService, incidentService);

        BotMessageService service = new BotMessageService(
                settingsService, client, new BotMessageParser(client), tools, channelStatusService, errorService, errorQueryGuard, errorQueryAuditService, imageGenService, mock(BotRuntimeStatus.class), botUserService, mock(BotBalanceService.class), mock(BotKeyUsageService.class), archive, imageFetcher, visionProperties, speechService, identityResolver, reportRenderer, chatClients, commandRouter, openAiStatusService, groupRedactor, forbiddenWordService, emailBindingService, lotteryService);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new QqBotWebhookController(properties, service, new ObjectMapper()))
                .build();
    }

    @Test
    void shouldHandleRealOneBotEventAndReply() throws Exception {
        // 字段照抄 OneBot v11 真实上报，多出来的字段（sub_type/font/sender）必须被忽略
        String body = """
                {"post_type":"message","message_type":"private","sub_type":"friend","message_id":1024,
                 "user_id":999,"self_id":1,"raw_message":"/号池","font":0,
                 "sender":{"user_id":999,"nickname":"me","role":"member"},
                 "message":[{"type":"text","data":{"text":"/号池"}}]}
                """;

        mockMvc.perform(post("/api/v1/bot/onebot")
                        .header("Authorization", "Bearer secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNoContent());

        verify(tools, timeout(3000)).poolReport(anyString());
        verify(client, timeout(3000)).sendPrivateMessage(eq(999L), contains("号池监控"));
    }

    @Test
    void shouldRejectWrongTokenOverHttp() throws Exception {
        mockMvc.perform(post("/api/v1/bot/onebot")
                        .header("Authorization", "Bearer nope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"post_type\":\"message\",\"message_type\":\"private\",\"user_id\":1}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(client);
    }
}
