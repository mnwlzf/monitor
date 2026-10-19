package com.monitor.platform.bot;

import com.monitor.platform.bot.order.BotOrderTools;
import com.monitor.platform.bot.search.WebSearchProperties;
import com.monitor.platform.bot.search.WebSearchTools;
import com.monitor.platform.bot.usage.BotUsageTools;
import com.monitor.platform.bot.weather.WeatherTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.mockito.Answers;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 人设与客户端工厂。
 *
 * <p>「只有管理员人设挂平台工具」是安全约束（陌生人绝不能拿到平台数据），
 * 这里固定住它；联网搜索是否挂给普通人由 {@link WebSearchProperties#isAllowNonAdmin()}
 * 控制，也在这里固定住。</p>
 */
class BotChatClientFactoryTest {

    private final MonitorChatTools tools = mock(MonitorChatTools.class);
    private final BotOrderTools orderTools = mock(BotOrderTools.class);
    private final BotUsageTools usageTools = mock(BotUsageTools.class);
    private final WeatherTools weatherTools = mock(WeatherTools.class);
    private final WebSearchTools webSearchTools = mock(WebSearchTools.class);
    private final WebSearchProperties webSearchProperties = new WebSearchProperties();

    @SuppressWarnings("unchecked")
    private BotChatClientFactory factory(ChatClient.Builder builder) {
        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(builder);
        return new BotChatClientFactory(
                tools, orderTools, usageTools, weatherTools,
                webSearchTools, webSearchProperties, provider);
    }

    private ChatClient.Builder fluentBuilder() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, Answers.RETURNS_SELF);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return builder;
    }

    private BotSettings settings() {
        return new BotSettings(true, Set.of(), Set.of(), Set.of(), true, "/", 900, 10);
    }

    @Test
    void onlyAdminPersonaCarriesPlatformTools() {
        assertThat(BotChatClientFactory.Persona.MONITOR_ADMIN.usesTools()).isTrue();
        assertThat(BotChatClientFactory.Persona.MONITOR_USER.usesTools()).isFalse();
        assertThat(BotChatClientFactory.Persona.CHAT.usesTools()).isFalse();
    }

    /** 管理员：功能启用就挂（没配 Key 时工具会提示运维去配）。 */
    @Test
    void adminGetsWebSearchWheneverEnabled() {
        ChatClient.Builder builder = fluentBuilder();

        factory(builder).get(BotChatClientFactory.Persona.MONITOR_ADMIN, settings());

        verify(builder).defaultTools(tools, orderTools, usageTools, weatherTools, webSearchTools);
    }

    /** 普通人默认不挂联网搜索。 */
    @Test
    void nonAdminDoesNotGetWebSearchByDefault() {
        ChatClient.Builder builder = fluentBuilder();

        factory(builder).get(BotChatClientFactory.Persona.MONITOR_USER, settings());

        verify(builder).defaultTools(weatherTools);
    }

    /** 配置 allow-non-admin=true 且配好 Key 之后，普通人才挂联网搜索。 */
    @Test
    void nonAdminGetsWebSearchWhenAllowedAndConfigured() {
        webSearchProperties.setAllowNonAdmin(true);
        webSearchProperties.setApiKey("test-key");
        ChatClient.Builder builder = fluentBuilder();

        factory(builder).get(BotChatClientFactory.Persona.CHAT, settings());

        verify(builder).defaultTools(weatherTools, webSearchTools);
    }

    /** 只开了 allow-non-admin、没配 Key：不发请求，也不要把「去配 Key」这种运维提示暴露给普通人。 */
    @Test
    void nonAdminDoesNotGetUnconfiguredWebSearch() {
        webSearchProperties.setAllowNonAdmin(true);
        ChatClient.Builder builder = fluentBuilder();

        factory(builder).get(BotChatClientFactory.Persona.MONITOR_USER, settings());

        verify(builder).defaultTools(weatherTools);
    }

    @Test
    void reportsUnavailableAndReturnsNullWhenNoBuilderConfigured() {
        BotChatClientFactory factory = factory(null);

        assertThat(factory.available()).isFalse();
        assertThat(factory.get(BotChatClientFactory.Persona.CHAT, settings())).isNull();
        // 一次确认不可用后保持不可用，不必反复探测
        assertThat(factory.available()).isFalse();
    }
}
