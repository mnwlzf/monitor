package com.monitor.platform.api.service;

import com.monitor.platform.api.dto.BotStatusResponse;
import com.monitor.platform.bot.BotMessageService;
import com.monitor.platform.bot.BotRuntimeStatus;
import com.monitor.platform.bot.BotSettings;
import com.monitor.platform.bot.BotSettingsService;
import com.monitor.platform.bot.image.ImageGenProperties;
import com.monitor.platform.bot.image.ImageGenService;
import com.monitor.platform.bot.onebot.OneBotClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 组装「机器人状态」页面的数据。
 *
 * <p>数据来源三块：进程内运行时计数（{@link BotRuntimeStatus}）、页面上的机器人配置
 * （{@link BotSettingsService}）、以及一次实时探活（{@link OneBotClient#probe()}）。</p>
 */
@Service
public class BotStatusService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BotRuntimeStatus runtimeStatus;
    private final BotSettingsService settingsService;
    private final OneBotClient oneBotClient;
    private final BotMessageService botMessageService;
    private final ImageGenService imageGenService;
    private final ImageGenProperties imageGenProperties;

    @Value("${spring.ai.openai.chat.options.model:}")
    private String chatModel;

    @Value("${spring.ai.openai.base-url:}")
    private String chatBaseUrl;

    public BotStatusService(BotRuntimeStatus runtimeStatus,
                            BotSettingsService settingsService,
                            OneBotClient oneBotClient,
                            BotMessageService botMessageService,
                            ImageGenService imageGenService,
                            ImageGenProperties imageGenProperties) {
        this.runtimeStatus = runtimeStatus;
        this.settingsService = settingsService;
        this.oneBotClient = oneBotClient;
        this.botMessageService = botMessageService;
        this.imageGenService = imageGenService;
        this.imageGenProperties = imageGenProperties;
    }

    /** 当前状态快照。 */
    public BotStatusResponse status() {
        BotSettings settings = settingsService.current();
        OneBotClient.ProbeResult probe = oneBotClient.probe();

        BotStatusResponse.Counters counters = new BotStatusResponse.Counters(
                runtimeStatus.inboundCount(),
                runtimeStatus.repliedCount(),
                runtimeStatus.droppedCount(),
                runtimeStatus.failedCount(),
                runtimeStatus.lastInboundAt(),
                runtimeStatus.lastOutboundAt());

        List<BotStatusResponse.DropView> drops = runtimeStatus.recentDrops().stream()
                .map(d -> new BotStatusResponse.DropView(
                        d.at(), d.channel(), d.groupId(), d.userId(), d.reason()))
                .toList();

        List<BotStatusResponse.ErrorView> errors = runtimeStatus.recentErrors().stream()
                .map(e -> new BotStatusResponse.ErrorView(e.at(), e.userId(), e.message()))
                .toList();

        return new BotStatusResponse(
                settings.enabled(),
                OffsetDateTime.ofInstant(runtimeStatus.startedAt(), ZONE),
                runtimeStatus.uptimeSeconds(),
                new BotStatusResponse.LlmInfo(botMessageService.llmAvailable(), chatModel, chatBaseUrl),
                new BotStatusResponse.OneBotInfo(
                        probe.configured(), probe.reachable(), probe.online(),
                        oneBotBaseUrl(), probe.selfId(), probe.nickname(), probe.error()),
                counters,
                runtimeStatus.dropReasons(),
                drops,
                errors,
                new BotStatusResponse.ImageGenInfo(
                        imageGenService.isUsable(),
                        imageGenProperties.getModel(),
                        imageGenProperties.getMaxPerMinute()));
    }

    private String oneBotBaseUrl() {
        return oneBotClient.baseUrl();
    }
}
