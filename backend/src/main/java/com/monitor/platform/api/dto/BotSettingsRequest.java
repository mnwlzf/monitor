package com.monitor.platform.api.dto;

import java.util.List;

/**
 * 保存 QQ 机器人设置的请求。
 *
 * <p>只有 {@code enabled} 是必填；其余字段为空时按默认值处理（前缀回退 "/"、
 * 回复长度回退 900、记忆窗口回退 10）。</p>
 *
 * <p>{@code platformGroups} 是「指定群（平台功能/OpenAI 状态）」——必须是 {@code allowedGroups} 的子集，
 * 超出子集范围会被拒绝保存。</p>
 */
public record BotSettingsRequest(
        boolean enabled,
        List<String> allowedGroups,
        List<String> allowedUsers,
        List<String> platformGroups,
        Boolean requireMention,
        String commandPrefix,
        Integer maxReplyLength,
        Integer memoryWindow,
        /** 是否允许非管理员（平台普通用户 / 陌生人）使用联网搜索；空按 false。 */
        Boolean webSearchForNonAdmin,
        /** 是否启用 Sub2API 错误突增主动告警；空按 false。 */
        Boolean errorAlertEnabled,
        /** 告警窗口分钟数，空按 15。 */
        Integer errorAlertWindowMinutes,
        /** 最小触发数量，空按 5。 */
        Integer errorAlertMinCount,
        /** 突增倍数，空按 1.5。 */
        Double errorAlertSpikeRatio,
        /** 冷却分钟数，空按 30。 */
        Integer errorAlertCooldownMinutes,
        /** 默认 MOSS 音色，空按 fnlp/MOSS-TTSD-v0.5:alex。 */
        String defaultVoice,
        /** 默认语音语气/风格，空按「自然、友好、简洁」。 */
        String defaultVoiceStyle
) {

    /** 兼容旧调用方：语音字段使用默认值。 */
    public BotSettingsRequest(boolean enabled, List<String> allowedGroups, List<String> allowedUsers,
                              List<String> platformGroups, Boolean requireMention, String commandPrefix,
                              Integer maxReplyLength, Integer memoryWindow, Boolean webSearchForNonAdmin,
                              Boolean errorAlertEnabled, Integer errorAlertWindowMinutes,
                              Integer errorAlertMinCount, Double errorAlertSpikeRatio,
                              Integer errorAlertCooldownMinutes) {
        this(enabled, allowedGroups, allowedUsers, platformGroups, requireMention, commandPrefix,
                maxReplyLength, memoryWindow, webSearchForNonAdmin, errorAlertEnabled,
                errorAlertWindowMinutes, errorAlertMinCount, errorAlertSpikeRatio, errorAlertCooldownMinutes,
                null, null);
    }
}