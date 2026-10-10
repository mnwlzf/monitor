package com.monitor.platform.bot;

import com.monitor.platform.bot.identity.BotIdentity;

/**
 * 平台功能的准入判定。
 *
 * <p><strong>不再只看邮箱</strong>：身份匹配之外，还要在「指定群」这一层再匹配一次群上下文，
 * 双重命中才触发平台功能。</p>
 *
 * <h2>规则</h2>
 * <ul>
 *     <li><b>管理员</b>：好友私聊 / 群临时会话放行；群里<strong>仅指定群</strong>放行；</li>
 *     <li><b>普通平台用户</b>：仅「指定群」及其临时会话放行，<strong>好友私聊一律不给</strong>；</li>
 *     <li><b>陌生人</b>：一律不给。</li>
 * </ul>
 *
 * <p>「指定群」是独立的配置项（{@link BotSettings#platformGroups()}），是「启用群」的子集；
 * 为空表示任何群都不给平台功能（fail-closed）。</p>
 */
public final class BotAccessPolicy {

    private BotAccessPolicy() {
    }

    /**
     * 平台功能是否可用。
     *
     * @param identity     发送者身份（邮箱匹配结果）
     * @param channel      消息通道
     * @param contextGroup 群上下文：群聊为本群、临时会话为发起群、好友私聊为 {@code null}
     * @param settings     当前机器人设置（提供「指定群」名单）
     */
    public static boolean platformFeatureAllowed(BotIdentity identity, BotChannel channel,
                                                 Long contextGroup, BotSettings settings) {
        if (identity == null || !identity.platformUser() || channel == null || settings == null) {
            return false;
        }
        boolean inDesignatedGroup = contextGroup != null && settings.isPlatformGroup(contextGroup);
        if (identity.admin()) {
            // 管理员：私聊（好友 / 临时会话）放行；群里只认指定群
            return channel != BotChannel.GROUP || inDesignatedGroup;
        }
        // 普通平台用户：只有「指定群」以及「指定群的临时会话」；好友私聊不给
        return inDesignatedGroup;
    }
}