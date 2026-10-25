package com.monitor.platform.status;

import com.monitor.platform.bot.BotSettings;
import com.monitor.platform.bot.BotSettingsService;
import com.monitor.platform.bot.QqBotProperties;
import com.monitor.platform.bot.onebot.OneBotClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI 官方服务状态查询与异常告警。
 *
 * <p>状态页字段默认是英文。对 QQ 用户展示时只使用中文映射：总体 indicator、组件名、
 * 组件状态和事件状态都翻译后输出，不再把英文原文直接抛给用户。</p>
 *
 * <p>告警发送到页面配置的「指定群（平台功能）」，与 /openai 命令复用同一份群配置。
 * 同一次连续异常只成功发送一次；恢复后状态清零，下一次异常重新发送。</p>
 */
@Service
public class OpenAiStatusService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiStatusService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DEFAULT_URL = "https://status.openai.com/";
    private static final Set<String> CLOSED_INCIDENT_STATUSES = Set.of("resolved", "completed");

    private static final Map<String, String> COMPONENT_NAMES = Map.ofEntries(
            Map.entry("Chat Completions", "对话补全"),
            Map.entry("Responses", "响应 API"),
            Map.entry("Agents", "智能体"),
            Map.entry("Decisions", "决策"),
            Map.entry("Fine-tuning", "微调"),
            Map.entry("Embeddings", "向量嵌入"),
            Map.entry("Images", "图像"),
            Map.entry("Batch", "批处理"),
            Map.entry("Audio", "音频"),
            Map.entry("Moderations", "内容审核"),
            Map.entry("Realtime", "实时 API"),
            Map.entry("Files", "文件"),
            Map.entry("Login", "登录"),
            Map.entry("Live", "实时"),
            Map.entry("Conversations", "会话"),
            Map.entry("ChatGPT Work", "ChatGPT 工作版"),
            Map.entry("Codex in ChatGPT Desktop", "ChatGPT 桌面版中的 Codex"),
            Map.entry("Compliance API", "合规 API"),
            Map.entry("Search", "搜索"),
            Map.entry("File uploads", "文件上传"),
            Map.entry("Voice mode", "语音模式"),
            Map.entry("GPTs", "GPTs"),
            Map.entry("Image Generation", "图像生成"),
            Map.entry("Deep Research", "深度研究"),
            Map.entry("Playground", "试验场"),
            Map.entry("Assistants", "助手 API"),
            Map.entry("Threads", "线程 API")
    );

    private final OpenAiStatusClient client;
    private final OpenAiStatusStateRepository stateRepository;
    private final BotSettingsService settingsService;
    private final QqBotProperties botProperties;
    private final OneBotClient oneBotClient;

    public OpenAiStatusService(OpenAiStatusClient client,
                               OpenAiStatusStateRepository stateRepository,
                               BotSettingsService settingsService,
                               QqBotProperties botProperties,
                               OneBotClient oneBotClient) {
        this.client = client;
        this.stateRepository = stateRepository;
        this.settingsService = settingsService;
        this.botProperties = botProperties;
        this.oneBotClient = oneBotClient;
    }

    /**
     * QQ 命令 / 自然语言查询使用的实时中文状态。
     *
     * <p>状态页不可用时返回中文提示，不把网络异常抛给消息处理线程。</p>
     */
    public String statusReport() {
        try {
            return formatSummary(client.fetch(), "【OpenAI 服务状态】", null);
        } catch (RuntimeException ex) {
            log.warn("查询 OpenAI 服务状态失败: {}", ex.getMessage());
            return "OpenAI 状态页暂时无法访问，请稍后重试。";
        }
    }

    /**
     * 定时任务入口。
     *
     * <p>读取状态页失败时向上抛出，让定时任务页显示 FAILED；这不是 OpenAI 服务异常，
     * 因此不会改变告警状态，也不会误发「服务异常」消息。</p>
     */
    public void checkAndNotify() {
        OpenAiStatusSummary summary = client.fetch();
        String indicator = normalizeIndicator(summary.status().indicator());
        String description = summary.status().description();
        boolean abnormal = !"none".equals(indicator);

        OpenAiStatusStateEntity state = stateRepository.get();
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        boolean wasActive = Boolean.TRUE.equals(state.getAlertActive());
        OffsetDateTime previousChangedAt = state.getLastChangedAt();
        boolean newEpisode = abnormal && !wasActive;

        state.setLastIndicator(indicator);
        state.setLastDescription(truncate(description, 255));
        state.setLastCheckedAt(now);

        if (!abnormal) {
            if (wasActive) {
                state.setLastChangedAt(now);
                state.setLastRecoveredAt(now);
                log.info("OpenAI 服务状态已恢复: indicator={}, 异常开始时间={}",
                        indicator, previousChangedAt);
            }
            state.setAlertActive(false);
            state.setAlertSent(false);
            stateRepository.save(state);
            return;
        }

        if (newEpisode) {
            state.setAlertActive(true);
            state.setAlertSent(false);
            state.setLastChangedAt(now);
            log.warn("OpenAI 服务状态异常: indicator={}, description={}",
                    indicator, description == null ? "" : description);
        }

        if (!Boolean.TRUE.equals(state.getAlertSent())) {
            boolean sent = sendToPlatformGroups(formatSummary(summary,
                    "【OpenAI 服务异常告警】",
                    "本次异常仅提醒一次；恢复正常后若再次异常，会重新提醒。"));
            if (sent) {
                state.setAlertSent(true);
                state.setLastAlertedAt(now);
            } else if (newEpisode) {
                log.warn("OpenAI 服务异常告警未送达任何指定群，将在下一轮继续尝试");
            }
        }

        stateRepository.save(state);
    }

    /**
     * 向「指定群（平台功能）」发送告警。
     *
     * <p>只使用页面已有配置，不新增收件人表。任意一个群发送成功即视为本次异常已提醒，
     * 避免某个群失效导致每 10 分钟重复轰炸其它群。</p>
     */
    private boolean sendToPlatformGroups(String text) {
        BotSettings settings = settingsService.current();
        if (!settings.enabled()) {
            log.warn("OpenAI 状态告警未发送：QQ 机器人运行时开关已关闭");
            return false;
        }
        if (!botProperties.isConfigured()) {
            log.warn("OpenAI 状态告警未发送：OneBot 未接通（enabled / api-base-url / webhook-token）");
            return false;
        }
        Set<String> groups = settings.platformGroups();
        if (groups == null || groups.isEmpty()) {
            log.warn("OpenAI 状态告警未发送：页面尚未配置「指定群（平台功能）」");
            return false;
        }

        int sent = 0;
        List<Long> failed = new ArrayList<>();
        for (String raw : groups) {
            Long groupId = parseGroupId(raw);
            if (groupId == null) {
                log.warn("OpenAI 状态告警跳过非法群号: {}", raw);
                continue;
            }
            try {
                if (oneBotClient.sendGroupMessage(groupId, text)) {
                    sent++;
                } else {
                    failed.add(groupId);
                }
            } catch (RuntimeException ex) {
                failed.add(groupId);
                log.warn("向指定群 {} 发送 OpenAI 状态告警失败: {}", groupId, ex.getMessage());
            }
        }
        log.info("OpenAI 状态告警发送完成: 成功 {}/{}, 失败群={}",
                sent, groups.size(), failed);
        return sent > 0;
    }

    private static Long parseGroupId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 生成中文状态文本。
     *
     * <p>只展示翻译后的总体状态、异常组件和进行中事件数量；事件标题本身来自运营人员自由填写，
     * 不直接展示英文原文，详情统一引导到官方状态页。</p>
     */
    private String formatSummary(OpenAiStatusSummary summary, String title, String footer) {
        String indicator = normalizeIndicator(summary.status().indicator());
        List<OpenAiStatusSummary.Component> components = summary.components();
        List<OpenAiStatusSummary.Component> abnormalComponents = components.stream()
                .filter(component -> component != null && isAbnormalComponent(component.status()))
                .toList();
        long activeIncidents = summary.incidents().stream()
                .filter(incident -> incident != null && isActiveIncident(incident.status()))
                .count();

        StringBuilder text = new StringBuilder(title).append('\n');
        text.append("总体状态：").append(indicatorLabel(indicator)).append('\n');
        text.append("状态页更新时间：").append(displayTime(summary)).append('\n');

        if (abnormalComponents.isEmpty()) {
            text.append("组件状态：");
            if (components.isEmpty()) {
                text.append("状态页暂未提供组件明细");
            } else {
                text.append(components.size()).append(" 个组件全部正常");
            }
            text.append('\n');
        } else {
            text.append("异常组件（").append(abnormalComponents.size()).append("）：\n");
            for (OpenAiStatusSummary.Component component : abnormalComponents) {
                text.append("· ").append(componentName(component.name()))
                        .append("：").append(componentStatusLabel(component.status())).append('\n');
            }
            int normalCount = Math.max(0, components.size() - abnormalComponents.size());
            text.append("正常组件：").append(normalCount).append(" 个\n");
        }

        if (activeIncidents > 0) {
            text.append("进行中事件：").append(activeIncidents).append(" 个\n");
        }
        text.append("详情：").append(statusPageUrl(summary));
        if (footer != null && !footer.isBlank()) {
            text.append('\n').append(footer);
        }
        return text.toString();
    }

    private static String normalizeIndicator(String raw) {
        String indicator = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return indicator.isBlank() ? "unknown" : indicator;
    }

    private static boolean isAbnormalComponent(String status) {
        return status != null && !"operational".equalsIgnoreCase(status.trim());
    }

    private static boolean isActiveIncident(String status) {
        return status != null && !CLOSED_INCIDENT_STATUSES.contains(status.trim().toLowerCase(Locale.ROOT));
    }

    private static String indicatorLabel(String indicator) {
        return switch (indicator) {
            case "none" -> "全部系统正常";
            case "minor" -> "轻微服务异常";
            case "major" -> "重大服务异常";
            case "critical" -> "严重服务异常";
            case "maintenance" -> "计划维护中";
            default -> "状态未知";
        };
    }

    private static String componentName(String name) {
        if (name == null || name.isBlank()) {
            return "未命名组件";
        }
        return COMPONENT_NAMES.getOrDefault(name, name);
    }

    private static String componentStatusLabel(String status) {
        if (status == null) {
            return "状态未知";
        }
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "operational" -> "正常";
            case "degraded_performance" -> "性能下降";
            case "partial_outage" -> "部分中断";
            case "major_outage" -> "严重中断";
            case "under_maintenance" -> "维护中";
            default -> "状态未知";
        };
    }

    private static String displayTime(OpenAiStatusSummary summary) {
        String raw = summary.page() == null ? null : summary.page().updatedAt();
        if (raw == null || raw.isBlank()) {
            return "未知";
        }
        try {
            return Instant.parse(raw).atZone(ZONE).format(TIME_FORMAT);
        } catch (RuntimeException ex) {
            return "未知";
        }
    }

    private static String statusPageUrl(OpenAiStatusSummary summary) {
        String url = summary.page() == null ? null : summary.page().url();
        return url == null || url.isBlank() ? DEFAULT_URL : url;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}