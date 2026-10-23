package com.monitor.platform.api.dto;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * 页面上的 QQ 机器人设置。
 *
 * @param enabled         运行时开关；false 时机器人收到消息也不响应（部署级总开关仍在环境变量）
 * @param allowedGroups   允许响应的群号（启用群）；为空表示不限制
 * @param allowedUsers    允许响应的私聊 QQ；为空表示不限制
 * @param platformGroups  平台功能群（指定群）；必须是启用群的子集；为空表示任何群都不给平台功能
 * @param requireMention  群里是否必须 @机器人
 * @param commandPrefix   命令前缀
 * @param maxReplyLength  单条回复最大字符数
 * @param memoryWindow    每个会话保留的历史消息条数
 * @param webSearchForNonAdmin 是否允许非管理员（平台普通用户 / 陌生人）使用联网搜索
 * @param errorAlertEnabled 是否启用 Sub2API 错误突增主动告警
 * @param errorAlertWindowMinutes 告警窗口分钟数
 * @param errorAlertMinCount 最小触发数量
 * @param errorAlertSpikeRatio 突增倍数
 * @param errorAlertCooldownMinutes 告警冷却分钟数
 * @param updatedAt       最后保存时间
 */
public record BotSettingsResponse(
        boolean enabled,
        Set<String> allowedGroups,
        Set<String> allowedUsers,
        Set<String> platformGroups,
        boolean requireMention,
        String commandPrefix,
        int maxReplyLength,
        int memoryWindow,
        boolean webSearchForNonAdmin,
        boolean errorAlertEnabled,
        int errorAlertWindowMinutes,
        int errorAlertMinCount,
        double errorAlertSpikeRatio,
        int errorAlertCooldownMinutes,
        OffsetDateTime updatedAt
) {
}
