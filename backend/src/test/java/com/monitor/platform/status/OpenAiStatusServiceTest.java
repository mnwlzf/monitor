package com.monitor.platform.status;

import com.monitor.platform.bot.BotSettings;
import com.monitor.platform.bot.BotSettingsService;
import com.monitor.platform.bot.QqBotProperties;
import com.monitor.platform.bot.onebot.OneBotClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpenAI 状态查询与告警状态机测试。
 */
class OpenAiStatusServiceTest {

    private final OpenAiStatusClient client = mock(OpenAiStatusClient.class);
    private final OpenAiStatusStateRepository stateRepository = mock(OpenAiStatusStateRepository.class);
    private final BotSettingsService settingsService = mock(BotSettingsService.class);
    private final QqBotProperties botProperties = new QqBotProperties();
    private final OneBotClient oneBotClient = mock(OneBotClient.class);
    private final OpenAiStatusStateEntity state = new OpenAiStatusStateEntity();

    private OpenAiStatusService service;

    @BeforeEach
    void setUp() {
        state.setId(1);
        state.setAlertActive(false);
        state.setAlertSent(false);

        botProperties.setEnabled(true);
        botProperties.setWebhookToken("secret");
        botProperties.setApiBaseUrl("http://onebot:3000");
        when(stateRepository.get()).thenReturn(state);
        when(settingsService.current()).thenReturn(settings(true, Set.of("123")));
        when(oneBotClient.sendGroupMessage(eq(123L), anyString())).thenReturn(true);

        service = new OpenAiStatusService(client, stateRepository, settingsService, botProperties, oneBotClient);
    }

    @Test
    void sendsOnlyOncePerAbnormalEpisodeAndAgainAfterRecovery() {
        when(client.fetch()).thenReturn(
                summary("minor", "Chat Completions", "degraded_performance"),
                summary("minor", "Chat Completions", "degraded_performance"),
                summary("none", "Chat Completions", "operational"),
                summary("major", "Responses", "major_outage"));

        service.checkAndNotify();
        service.checkAndNotify();
        service.checkAndNotify();
        service.checkAndNotify();

        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(oneBotClient, times(2)).sendGroupMessage(eq(123L), messages.capture());
        assertThat(messages.getAllValues().get(0))
                .contains("查询时间：")
                .contains("状态页最后更新：2026-10-25 10:03:04")
                .contains("轻微服务异常")
                .contains("对话补全：性能下降")
                .doesNotContain("degraded_performance");
        assertThat(messages.getAllValues().get(1))
                .contains("重大服务异常")
                .contains("响应 API：严重中断");
        assertThat(state.getAlertActive()).isTrue();
        assertThat(state.getAlertSent()).isTrue();
    }

    @Test
    void indicatorChangesDuringSameEpisodeDoNotResend() {
        when(client.fetch()).thenReturn(
                summary("minor", "Chat Completions", "degraded_performance"),
                summary("major", "Chat Completions", "major_outage"));

        service.checkAndNotify();
        service.checkAndNotify();

        verify(oneBotClient, times(1)).sendGroupMessage(eq(123L), anyString());
        assertThat(state.getLastIndicator()).isEqualTo("major");
    }

    @Test
    void retriesUntilOneGroupAcceptsTheAlert() {
        when(client.fetch()).thenReturn(summary("minor", "Audio", "degraded_performance"));
        when(oneBotClient.sendGroupMessage(eq(123L), anyString())).thenReturn(false, true);

        service.checkAndNotify();
        assertThat(state.getAlertSent()).isFalse();

        service.checkAndNotify();
        assertThat(state.getAlertSent()).isTrue();

        verify(oneBotClient, times(2)).sendGroupMessage(eq(123L), anyString());
    }

    @Test
    void doesNotMarkSentWhenNoPlatformGroupIsConfigured() {
        when(client.fetch()).thenReturn(summary("maintenance", "Files", "under_maintenance"));
        when(settingsService.current()).thenReturn(settings(true, Set.of()));

        service.checkAndNotify();

        verify(oneBotClient, times(0)).sendGroupMessage(eq(123L), anyString());
        assertThat(state.getAlertActive()).isTrue();
        assertThat(state.getAlertSent()).isFalse();
    }

    @Test
    void statusReportIsFullyTranslatedToChinese() {
        when(client.fetch()).thenReturn(summaryWithIncident(
                "minor", "Chat Completions", "degraded_performance"));

        String report = service.statusReport();

        assertThat(report)
                .contains("OpenAI 服务状态")
                .contains("查询时间：")
                .contains("状态页最后更新：2026-10-25 10:03:04")
                .contains("轻微服务异常")
                .contains("对话补全：性能下降")
                .contains("进行中事件：1 个")
                .doesNotContain("degraded_performance")
                .doesNotContain("All Systems Operational");
    }

    @Test
    void statusReportReturnsChineseFallbackWhenApiFails() {
        when(client.fetch()).thenThrow(new IllegalStateException("timeout"));

        assertThat(service.statusReport()).isEqualTo("OpenAI 状态页暂时无法访问，请稍后重试。");
    }

    private static BotSettings settings(boolean enabled, Set<String> platformGroups) {
        return new BotSettings(enabled, Set.of("123"), Set.of(), platformGroups,
                true, "/", 900, 10, false, false, 15, 5, 1.5, 30);
    }

    private static OpenAiStatusSummary summary(String indicator, String componentName, String componentStatus) {
        return summaryWithIncident(indicator, componentName, componentStatus, List.of());
    }

    private static OpenAiStatusSummary summaryWithIncident(String indicator,
                                                           String componentName,
                                                           String componentStatus) {
        OpenAiStatusSummary.Incident incident = new OpenAiStatusSummary.Incident(
                "Elevated errors on API", "investigating", "minor",
                "https://stspg.io/test", "2026-10-25T01:00:00Z", "2026-10-25T02:00:00Z");
        return summaryWithIncident(indicator, componentName, componentStatus, List.of(incident));
    }

    private static OpenAiStatusSummary summaryWithIncident(String indicator,
                                                           String componentName,
                                                           String componentStatus,
                                                           List<OpenAiStatusSummary.Incident> incidents) {
        return new OpenAiStatusSummary(
                new OpenAiStatusSummary.Page("OpenAI", "https://status.openai.com/", "2026-10-25T02:03:04Z"),
                new OpenAiStatusSummary.Status("All Systems Operational", indicator),
                List.of(new OpenAiStatusSummary.Component(componentName, componentStatus)),
                incidents);
    }
}
