package com.monitor.platform.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitor.platform.bot.identity.BotIdentity;
import com.monitor.platform.bot.identity.BotIdentityResolver;
import com.monitor.platform.bot.identity.QqUserBindingService;
import com.monitor.platform.bot.onebot.OneBotClient;
import com.monitor.platform.bot.report.BotReport;
import com.monitor.platform.bot.report.BotReportRenderer;
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
                true, java.util.Set.of(), java.util.Set.of(), true, "/", 900, 10));

        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        // 身份识别在单元测试里用桩：这里验证的是 HTTP → 路由 → 出站调用的链路
        BotIdentityResolver identityResolver = mock(BotIdentityResolver.class);
        when(identityResolver.resolve(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new BotIdentity(true, true, "admin@qq.com"));
        QqUserBindingService bindings = mock(QqUserBindingService.class);
        // 这里的回复很短，不会走图片渲染；仅需满足构造依赖
        BotReportRenderer reportRenderer = mock(BotReportRenderer.class);

        BotMessageService service = new BotMessageService(
                settingsService, client, tools, identityResolver, bindings, reportRenderer, provider);
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