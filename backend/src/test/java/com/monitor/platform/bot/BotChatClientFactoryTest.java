package com.monitor.platform.bot;

import com.monitor.platform.bot.error.BotErrorTools;
import com.monitor.platform.bot.key.Sub2KeyTools;
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
 * <p>「只有管理员人设挂平台工具」是安全约束（陌生人绝不能拿到平台数据），这里固定住它；
 * 联网搜索是否挂给普通人由页面设置 {@link BotSettings#webSearchForNonAdmin()} 控制。</p>
 */
class BotChatClientFactoryTest {

    private final MonitorChatTools tools = mock(MonitorChatTools.class);
    private final BotErrorTools errorTools = mock(BotErrorTools.class);
    private final BotOrderTools orderTools = mock(BotOrderTools.class);
    private final BotUsageTools usageTools = mock(BotUsageTools.class);
    private final WeatherTools weatherTools = mock(WeatherTools.class);
    private final WebSearchTools webSearchTools = mock(WebSearchTools.class);
    private final Sub2KeyTools sub2KeyTools = mock(Sub2KeyTools.class);
    private final WebSearchProperties webSearchProperties = new WebSearchProperties();

    @SuppressWarnings("unchecked")
    private BotChatClientFactory factory(ChatClient.Builder builder) {
        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(builder);
        return new BotChatClientFactory(
                tools, errorTools, orderTools, usageTools, weatherTools,
                webSearchTools, sub2KeyTools, webSearchProperties, provider);
    }

    private ChatClient.Builder fluentBuilder() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, Answers.RETURNS_SELF);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return builder;
    }

    private BotSettings settings(boolean webSearchForNonAdmin) {
        return new BotSettings(true, Set.of(), Set.of(), Set.of(), true, "/", 900, 10,
                webSearchForNonAdmin);
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

        factory(builder).get(BotChatClientFactory.Persona.MONITOR_ADMIN, settings(false), true);

        verify(builder).defaultTools(tools, errorTools, orderTools, usageTools, weatherTools, webSearchTools, sub2KeyTools);
    }

    /**
     * 管理员即使不在指定群（人设退化成 CHAT），联网搜索也照常可用。
     *
     * <p>这是之前的缺陷：按人设判断时，管理员在非指定群会被当成普通人，开关没开就没有联网搜索。</p>
     */
    @Test
    void adminGetsWebSearchEvenWithChatPersona() {
        ChatClient.Builder builder = fluentBuilder();

        factory(builder).get(BotChatClientFactory.Persona.CHAT, settings(false), true);

        verify(builder).defaultTools(weatherTools, webSearchTools);
    }

    /** 普通人：页面开关默认关闭，不挂联网搜索。 */
    @Test
    void nonAdminDoesNotGetWebSearchByDefault() {
        ChatClient.Builder builder = fluentBuilder();

        factory(builder).get(BotChatClientFactory.Persona.MONITOR_USER, settings(false), false);

        verify(builder).defaultTools(weatherTools);
    }

    /** 页面开关打开 + 配好 Key 后，普通人才挂联网搜索。 */
    @Test
    void nonAdminGetsWebSearchWhenEnabledOnPageAndConfigured() {
        webSearchProperties.setApiKey("test-key");
        ChatClient.Builder builder = fluentBuilder();

        factory(builder).get(BotChatClientFactory.Persona.CHAT, settings(true), false);

        verify(builder).defaultTools(weatherTools, webSearchTools);
    }

    /** 页面开关打开、但服务端没配 Key：不挂（避免把「去配 Key」的运维提示暴露给普通人）。 */
    @Test
    void nonAdminDoesNotGetUnconfiguredWebSearch() {
        ChatClient.Builder builder = fluentBuilder();

        factory(builder).get(BotChatClientFactory.Persona.MONITOR_USER, settings(true), false);

        verify(builder).defaultTools(weatherTools);
    }

    /** 页面开关变化后要重建客户端，不能只按记忆窗口缓存。 */
    @Test
    void rebuildsClientWhenWebSearchToggleChanges() {
        webSearchProperties.setApiKey("test-key");
        ChatClient.Builder builder = fluentBuilder();
        BotChatClientFactory factory = factory(builder);

        factory.get(BotChatClientFactory.Persona.MONITOR_USER, settings(false), false);
        factory.get(BotChatClientFactory.Persona.MONITOR_USER, settings(true), false);

        verify(builder).defaultTools(weatherTools);
        verify(builder).defaultTools(weatherTools, webSearchTools);
    }

    @Test
    void reportsUnavailableAndReturnsNullWhenNoBuilderConfigured() {
        BotChatClientFactory factory = factory(null);

        assertThat(factory.available()).isFalse();
        assertThat(factory.get(BotChatClientFactory.Persona.CHAT, settings(false), false)).isNull();
        // 一次确认不可用后保持不可用，不必反复探测
        assertThat(factory.available()).isFalse();
    }
}
