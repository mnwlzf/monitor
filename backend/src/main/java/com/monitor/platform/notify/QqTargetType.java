package com.monitor.platform.notify;

/**
 * QQ 消息接收对象类型。
 *
 * <p>与 OneBot 的发送接口一一对应：{@code GROUP} 调 {@code /send_group_msg}，
 * {@code PRIVATE} 调 {@code /send_private_msg}。</p>
 */
public enum QqTargetType {

    /** 群消息。 */
    GROUP,

    /** 私聊消息。 */
    PRIVATE;

    /** 解析数据库 / 请求里的类型值；无法识别时返回 {@code null}。 */
    public static QqTargetType parse(String raw) {
        if (raw == null) {
            return null;
        }
        for (QqTargetType type : values()) {
            if (type.name().equalsIgnoreCase(raw.trim())) {
                return type;
            }
        }
        return null;
    }

    /** 展示名：群 / 私聊（日志里用）。 */
    public String label() {
        return this == GROUP ? "群" : "私聊";
    }
}
