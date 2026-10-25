package com.monitor.platform.bot;

import java.util.Set;

/**
 * 机器人运行时设置（数据库中那一行的内存快照）。
 *
 * <p>{@code allowedGroups} / {@code allowedUsers} 为空表示不限制。</p>
 *
 * <p>{@code platformGroups} 是「指定群」——控制平台功能和 OpenAI 公开状态查询；
 * 群内可以正常查询，但最终输出会自动隐藏账号名称、上游平台名称/地址和邮箱。
 * 它是 {@code allowedGroups} 的子集，为空表示任何群都不给平台功能（fail-closed）。</p>
 */
public record BotSettings(
        boolean enabled,
        Set<String> allowedGroups,
        Set<String> allowedUsers,
        Set<String> platformGroups,
        boolean requireMention,
        String commandPrefix,
        int maxReplyLength,
        int memoryWindow,
        /** 是否允许非管理员（平台普通用户 / 陌生人）使用联网搜索（页面可配）。 */
        boolean webSearchForNonAdmin,
        /** 是否启用 Sub2API 错误突增主动告警。 */
        boolean errorAlertEnabled,
        /** 错误告警当前窗口与对比窗口的分钟数。 */
        int errorAlertWindowMinutes,
        /** 触发告警的最小错误数/429数/入口拒绝数。 */
        int errorAlertMinCount,
        /** 相对上一周期的突增倍数。 */
        double errorAlertSpikeRatio,
        /** 同一告警的冷却分钟数。 */
        int errorAlertCooldownMinutes
) {

    public boolean isGroupAllowed(Long groupId) {
        return matches(allowedGroups, groupId);
    }

    public boolean isUserAllowed(Long userId) {
        return matches(allowedUsers, userId);
    }

    /**
     * 是否「指定群」（OpenAI 公开状态群）。
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
