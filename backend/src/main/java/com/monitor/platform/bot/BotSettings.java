package com.monitor.platform.bot;

import java.util.Set;

/**
 * 机器人运行时设置（数据库中那一行的内存快照）。
 *
 * <p>{@code allowedGroups} / {@code allowedUsers} 为空表示不限制。</p>
 *
 * <p>{@code platformGroups} 是「指定群」——只有这些群（以及从这些群发起的临时会话）
 * 才触发平台功能。它是 {@code allowedGroups} 的子集，且语义与白名单相反：
 * <strong>为空表示任何群都不给平台功能</strong>（fail-closed）。</p>
 */
public record BotSettings(
        boolean enabled,
        Set<String> allowedGroups,
        Set<String> allowedUsers,
        Set<String> platformGroups,
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

    /**
     * 是否「指定群」（平台功能群）。
     *
     * <p>与白名单不同：留空表示<strong>一个都不给</strong>，而不是不限制。</p>
     */
    public boolean isPlatformGroup(Long groupId) {
        return platformGroups != null && groupId != null
                && platformGroups.contains(String.valueOf(groupId));
    }

    private static boolean matches(Set<String> whitelist, Long id) {
        if (whitelist == null || whitelist.isEmpty()) {
            return true;
        }
        return id != null && whitelist.contains(String.valueOf(id));
    }
}