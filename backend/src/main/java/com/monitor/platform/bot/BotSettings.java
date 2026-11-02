package com.monitor.platform.bot;

import java.util.List;
import java.util.Set;

/**
 * 机器人运行时设置（数据库中那一行的内存快照）。
 *
 * <p>{@code allowedGroups} / {@code allowedUsers} 为空表示不限制。</p>
 *
 * <p>{@code platformGroups} 是「指定群」——控制平台功能和 OpenAI 公开状态查询；
 * 群内可以正常查询，但最终输出会自动隐藏账号名称、上游平台名称/地址和邮箱。
 * 它是 {@code allowedGroups} 的子集，为空表示任何群都不给平台功能（fail-closed）。</p>
 *
 * <p>{@code groupPolicies} 按群覆盖「未 @机器人时是否回复」的策略；未配置的群沿用
 * {@code requireMention}。明确 @机器人 始终会触发回复。</p>
 */
public record BotSettings(
        boolean enabled,
        Set<String> allowedGroups,
        Set<String> allowedUsers,
        Set<String> platformGroups,
        List<BotGroupReplyPolicy> groupPolicies,
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
        int errorAlertCooldownMinutes,
        /** 默认 Qwen-Audio-TTS 音色（页面可配），例如 longanhuan_v3.1。 */
        String defaultVoice,
        /** 默认语气/风格（页面可配），用于组织语音回复的文字措辞。 */
        String defaultVoiceStyle
) {

    /** 兼容旧测试/调用方：新增群策略与语音字段使用内置默认值。 */
    public BotSettings(boolean enabled,
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
                       int errorAlertCooldownMinutes) {
        this(enabled, allowedGroups, allowedUsers, platformGroups, List.of(), requireMention,
                commandPrefix, maxReplyLength, memoryWindow, webSearchForNonAdmin, errorAlertEnabled,
                errorAlertWindowMinutes, errorAlertMinCount, errorAlertSpikeRatio, errorAlertCooldownMinutes,
                "longanhuan_v3.1", "自然、友好、简洁");
    }

    /** 兼容旧调用方：没有按群策略时使用空列表。 */
    public BotSettings(boolean enabled,
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
                       String defaultVoice,
                       String defaultVoiceStyle) {
        this(enabled, allowedGroups, allowedUsers, platformGroups, List.of(), requireMention,
                commandPrefix, maxReplyLength, memoryWindow, webSearchForNonAdmin, errorAlertEnabled,
                errorAlertWindowMinutes, errorAlertMinCount, errorAlertSpikeRatio, errorAlertCooldownMinutes,
                defaultVoice, defaultVoiceStyle);
    }

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

    /** 查找某个群的定制策略；没有单独配置时返回 {@code null}。 */
    public BotGroupReplyPolicy groupPolicy(Long groupId) {
        if (groupId == null || groupPolicies == null || groupPolicies.isEmpty()) {
            return null;
        }
        String id = String.valueOf(groupId);
        for (BotGroupReplyPolicy policy : groupPolicies) {
            if (policy != null && id.equals(policy.groupId())) {
                return policy;
            }
        }
        return null;
    }

    /** 群绑定音色优先；未绑定时使用全局默认音色。 */
    public String voiceForGroup(Long groupId) {
        BotGroupReplyPolicy policy = groupPolicy(groupId);
        return policy != null && policy.voice() != null && !policy.voice().isBlank()
                ? policy.voice() : defaultVoice;
    }

    /**
     * 判断一条群消息是否应触发机器人。
     *
     * <p>明确 @机器人 永远触发；未 @时优先使用群定制策略，未配置则沿用全局
     * {@code requireMention}。</p>
     *
     * @param randomValue [0, 1) 的随机数，仅随机策略使用
     */
    public boolean shouldRespondInGroup(Long groupId, boolean mentioned, String text, double randomValue) {
        if (mentioned) {
            return true;
        }
        BotGroupReplyPolicy policy = groupPolicy(groupId);
        if (policy != null) {
            if (policy.strategy() == BotGroupReplyStrategy.INHERIT) {
                return !requireMention;
            }
            return policy.shouldTrigger(text, randomValue);
        }
        return !requireMention;
    }

    private static boolean matches(Set<String> whitelist, Long id) {
        if (whitelist == null || whitelist.isEmpty()) {
            return true;
        }
        return id != null && whitelist.contains(String.valueOf(id));
    }
}
