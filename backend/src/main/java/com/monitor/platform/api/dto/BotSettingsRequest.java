package com.monitor.platform.api.dto;

import java.util.List;

/**
 * 保存 QQ 机器人设置的请求。
 *
 * <p>只有 {@code enabled} 是必填；其余字段为空时按默认值处理（前缀回退 "/"、
 * 回复长度回退 900、记忆窗口回退 10）。</p>
 */
public record BotSettingsRequest(
        boolean enabled,
        List<String> allowedGroups,
        List<String> allowedUsers,
        Boolean requireMention,
        String commandPrefix,
        Integer maxReplyLength,
        Integer memoryWindow
) {
}