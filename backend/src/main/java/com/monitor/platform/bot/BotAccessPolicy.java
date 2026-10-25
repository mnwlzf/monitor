package com.monitor.platform.bot;

import com.monitor.platform.bot.identity.BotIdentity;

/**
 * 平台功能的准入判定。
 *
 * <p><strong>隐私边界：</strong>任何群聊或从群发起的临时会话，都不允许查询上游平台、
 * 账号、余额、号池、密钥、渠道、变更等私有信息，即使发送者是管理员也一样。</p>
 *
 * <p>管理员只有在好友私聊中才能使用平台级数据；普通平台用户不能查询平台级数据。
 * OpenAI 官方服务状态属于公开信息，单独通过 {@link #openAiStatusAllowed} 放行，
 * 不受群内平台数据禁令影响。</p>
 */
public final class BotAccessPolicy {

    private BotAccessPolicy() {
    }

    /**
     * 是否允许平台级/上游私有数据。
     *
     * <p>群聊和群临时会话一律返回 {@code false}，避免任何群成员截图或转发造成隐私泄露。
     * 管理员好友私聊仍可使用；普通用户和陌生人始终不可用。</p>
     */
    public static boolean platformFeatureAllowed(BotIdentity identity, BotChannel channel,
                                                 Long contextGroup, BotSettings settings) {
        if (channel == BotChannel.GROUP || contextGroup != null) {
            return false;
        }
        return identity != null
                && identity.platformUser()
                && identity.admin()
                && channel != null
                && settings != null;
    }

    /**
     * 是否允许查询 OpenAI 官方服务状态。
     *
     * <p>这是公开信息，沿用原来的“指定群 + 平台身份”口径：管理员在好友私聊、
     * 管理员和平台用户在指定群/指定群临时会话中可用。它不会返回任何本平台数据。</p>
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
