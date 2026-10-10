package com.monitor.platform.bot;

import com.monitor.platform.bot.onebot.OneBotEvent;

/**
 * 一条 QQ 消息的通道形态。
 *
 * <p>三种形态在 OneBot v11 里靠 {@code message_type} + {@code sub_type} 区分：</p>
 * <ul>
 *     <li>{@link #GROUP}：群聊，{@code message_type=group}；</li>
 *     <li>{@link #TEMP_SESSION}：群临时会话，{@code message_type=private} + {@code sub_type=group}，
 *         {@code group_id} 是发起该会话的群；</li>
 *     <li>{@link #PRIVATE}：好友私聊，{@code message_type=private} 且 {@code sub_type != group}。</li>
 * </ul>
 */
public enum BotChannel {

    /** 群聊：在群里 @机器人。 */
    GROUP,

    /** 群临时会话：在群里点机器人头像发起的私聊。 */
    TEMP_SESSION,

    /** 好友私聊：加了好友直接私聊。 */
    PRIVATE;

    /**
     * 解析一条事件的通道形态；非消息事件返回 {@code null}。
     *
     * <p>临时会话是否带 {@code group_id} 取决于 OneBot 实现（NapCat 一般会带）。
     * 这里只负责判定形态，取不到群号时由上层按「不在指定群」处理（fail-closed）。</p>
     */
    public static BotChannel of(OneBotEvent event) {
        if (event == null) {
            return null;
        }
        if ("group".equals(event.messageType())) {
            return GROUP;
        }
        if ("private".equals(event.messageType())) {
            return "group".equals(event.subType()) ? TEMP_SESSION : PRIVATE;
        }
        return null;
    }

    /** 是否带群上下文：群聊取本群，临时会话取发起群，好友私聊没有。 */
    public boolean hasGroupContext() {
        return this == GROUP || this == TEMP_SESSION;
    }
}