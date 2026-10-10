package com.monitor.platform.bot;

import java.util.Set;

/**
 * 机器人运行时设置（数据库中那一行的内存快照）。
 *
 * <p>{@code allowedGroups} / {@code allowedUsers} 为空表示不限制。</p>
 */
public record BotSettings(
        boolean enabled,
        Set<String> allowedGroups,
        Set<String> allowedUsers,
        boolean requireMention,
        String commandPrefix,
        int maxReplyLength,
        int memoryWindow
) {

    public boolean isGroupAllowed(Long groupId) {
        return matches(allowedGroups, groupId);
    }

    public boolean isUserAllowed(Long userId) {
        return matches(allowedUsers, userId);
    }

    private static boolean matches(Set<String> whitelist, Long id) {
        if (whitelist == null || whitelist.isEmpty()) {
            return true;
        }
        return id != null && whitelist.contains(String.valueOf(id));
    }
}