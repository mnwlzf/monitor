package com.monitor.platform.bot.identity;

/**
 * 一条 QQ 消息的发送者身份。
 *
 * @param platformUser 是否为自建 Sub2API 平台的用户；false 时机器人只能普通聊天
 * @param admin        是否为平台管理员；只有 true 才能查询平台级数据
 * @param email        命中的邮箱（未命中为 null）
 */
public record BotIdentity(boolean platformUser, boolean admin, String email) {

    /** 陌生人 / 非平台用户。 */
    public static BotIdentity guest() {
        return new BotIdentity(false, false, null);
    }

    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }
}