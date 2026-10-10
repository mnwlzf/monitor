package com.monitor.platform.api.dto;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * 页面上的 QQ 机器人设置。
 *
 * @param enabled         运行时开关；false 时机器人收到消息也不响应（部署级总开关仍在环境变量）
 * @param allowedGroups   允许响应的群号；为空表示不限制
 * @param allowedUsers    允许响应的私聊 QQ；为空表示不限制
 * @param requireMention  群里是否必须 @机器人
 * @param commandPrefix   命令前缀
 * @param maxReplyLength  单条回复最大字符数
 * @param memoryWindow    每个会话保留的历史消息条数
 * @param updatedAt       最后保存时间
 */
public record BotSettingsResponse(
        boolean enabled,
        Set<String> allowedGroups,
        Set<String> allowedUsers,
        boolean requireMention,
        String commandPrefix,
        int maxReplyLength,
        int memoryWindow,
        OffsetDateTime updatedAt
) {
}