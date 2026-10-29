package com.monitor.platform.bot;

import com.monitor.platform.bot.identity.BotIdentity;

/**
 * 平台功能的准入判定。
 *
 * <p>群聊和群临时会话<strong>可以正常触发平台查询</strong>，但最终输出必须经过
 * {@code BotGroupRedactor} 脱敏；非管理员好友私聊同样必须脱敏，只有管理员好友私聊保留原始明细。</p>
 *
 * <p>权限口径与原来一致：管理员好友私聊、管理员/平台用户在指定群及指定群临时会话中
 * 可使用平台功能；陌生人始终不可用。OpenAI 官方状态属于公开信息，单独放行。</p>
 */
public final class BotAccessPolicy {

    private BotAccessPolicy() {
    }

    /**
     * 是否允许平台级/上游数据查询。
     *
     * <p>群内允许执行，但调用方必须在发送前做输出脱敏。普通用户仍不能查询平台级数据。</p>
     */
    public static boolean platformFeatureAllowed(BotIdentity identity, BotChannel channel,
                                                 Long contextGroup, BotSettings settings) {
        return openAiStatusAllowed(identity, channel, contextGroup, settings);
    }

    /** 抽奖只允许在配置的「指定群」或指定群的临时会话中触发，私聊不参与。 */
    public static boolean lotteryAllowed(BotIdentity identity, BotChannel channel,
                                         Long contextGroup, BotSettings settings) {
        return channel != null
                && contextGroup != null
                && settings != null
                && settings.isPlatformGroup(contextGroup);
    }

    /**
     * 是否允许查询 OpenAI 官方服务状态。
     *
     * <p>这是公开信息：管理员在好友私聊、管理员和平台用户在指定群/指定群临时会话中可用。</p>
     */
    public static boolean openAiStatusAllowed(BotIdentity identity, BotChannel channel,
                                              Long contextGroup, BotSettings settings) {
        if (identity == null || !identity.platformUser() || channel == null || settings == null) {
            return false;
        }
        boolean inDesignatedGroup = contextGroup != null && settings.isPlatformGroup(contextGroup);
        if (identity.admin()) {
            // 管理员：好友私聊 / 群临时会话放行；群里只认指定群
            return channel != BotChannel.GROUP || inDesignatedGroup;
        }
        // 普通平台用户：只有指定群以及指定群的临时会话
        return inDesignatedGroup;
    }
}
