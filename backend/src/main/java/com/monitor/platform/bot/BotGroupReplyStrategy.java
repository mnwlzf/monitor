package com.monitor.platform.bot;

/**
 * 单个群聊的回复触发策略。
 *
 * <p>无论选择哪种策略，明确 @机器人 都会触发回复；这里只控制「没有 @ 时」是否响应。</p>
 */
public enum BotGroupReplyStrategy {

    /** 未 @ 时沿用全局 requireMention；用于只绑定人设/音色而不覆盖触发策略。 */
    INHERIT,

    /** 仅响应 @机器人（未单独配置的群沿用全局 requireMention 设置）。 */
    MENTION,

    /** 主动回复：群内每条消息都交给机器人处理。 */
    PROACTIVE,

    /** 随机回复：按配置的概率随机响应群消息。 */
    RANDOM,

    /** 关键词唤醒：消息包含任一关键词时响应。 */
    KEYWORD
}
