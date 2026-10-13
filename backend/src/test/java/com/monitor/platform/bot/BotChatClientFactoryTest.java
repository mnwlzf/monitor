package com.monitor.platform.bot;

import com.monitor.platform.bot.order.BotOrderTools;
import com.monitor.platform.bot.usage.BotUsageTools;
import com.monitor.platform.bot.weather.WeatherTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 人设与客户端工厂。
 *
 * <p>「只有管理员人设挂平台工具」是安全约束（陌生人绝不能拿到平台数据），
 * 这里固定住它，避免以后调工具集时被改坏。</p>
 */
class BotChatClientFactoryTest {

    @SuppressWarnings("unchecked")
    private BotChatClientFactory factory(ChatClient.Builder builder) {
        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(builder);
        return new BotChatClientFactory(
                mock(MonitorChatTools.class),
                mock(BotOrderTools.class),
                mock(BotUsageTools.class),
                mock(WeatherTools.class),
                provider);
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

    @Test
    void reportsUnavailableAndReturnsNullWhenNoBuilderConfigured() {
        BotChatClientFactory factory = factory(null);

        assertThat(factory.available()).isFalse();
        assertThat(factory.get(BotChatClientFactory.Persona.CHAT, settings())).isNull();
        // 一次确认不可用后保持不可用，不必反复探测
        assertThat(factory.available()).isFalse();
    }
}
