package com.monitor.platform.bot;

import java.util.List;
import java.util.Locale;

/**
 * 单个群聊的回复策略。
 *
 * @param groupId       群号
 * @param groupName     OneBot 自动获取的群名称
 * @param groupAlias    管理员手动设置的显示昵称，优先级高于群名称
 * @param personaId     绑定的人设 ID；null 表示使用默认人设
 * @param voice         绑定的音色；null 表示使用全局默认音色
 * @param strategy      未 @ 机器人时的触发策略
 * @param randomPercent 随机回复概率（1-100），仅 {@link BotGroupReplyStrategy#RANDOM} 使用
 * @param keywords      唤醒关键词，仅 {@link BotGroupReplyStrategy#KEYWORD} 使用
 */
public record BotGroupReplyPolicy(
        String groupId,
        String groupName,
        String groupAlias,
        Long personaId,
        String voice,
        BotGroupReplyStrategy strategy,
        int randomPercent,
        List<String> keywords
) {

    /** 兼容旧配置：没有群昵称。 */
    public BotGroupReplyPolicy(String groupId, Long personaId, String voice,
                               BotGroupReplyStrategy strategy, int randomPercent, List<String> keywords) {
        this(groupId, null, null, personaId, voice, strategy, randomPercent, keywords);
    }

    /** 兼容旧配置：没有绑定音色时使用默认音色。 */
    public BotGroupReplyPolicy(String groupId, Long personaId, BotGroupReplyStrategy strategy,
                               int randomPercent, List<String> keywords) {
        this(groupId, null, null, personaId, null, strategy, randomPercent, keywords);
    }

    /** 兼容旧配置：没有绑定人设和音色时使用默认值。 */
    public BotGroupReplyPolicy(String groupId, BotGroupReplyStrategy strategy,
                               int randomPercent, List<String> keywords) {
        this(groupId, null, null, null, null, strategy, randomPercent, keywords);
    }

    public BotGroupReplyPolicy {
        strategy = strategy == null ? BotGroupReplyStrategy.MENTION : strategy;
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    /**
     * 判断一条未 @机器人 的群消息是否应触发回复。
     *
     * @param text        群消息文本
     * @param randomValue [0, 1) 的随机数，仅随机策略使用
     */
    public boolean shouldTrigger(String text, double randomValue) {
        return switch (strategy) {
            case INHERIT -> false;
            case MENTION -> false;
            case PROACTIVE -> true;
            case RANDOM -> randomValue < randomPercent / 100.0;
            case KEYWORD -> containsKeyword(text);
            // 是否接话需要异步调用大模型，不能在这里同步决定；先放行，后续由消息服务判定。
            case AI_DECIDE -> true;
        };
    }

    private boolean containsKeyword(String text) {
        if (text == null || text.isBlank() || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank()
                    && normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
