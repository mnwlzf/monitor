package com.monitor.platform.bot.user;

import java.time.OffsetDateTime;

/**
 * Sub2API 管理端返回的用户 API Key（**已打码**，绝不携带明文密钥）。
 *
 * @param id         密钥 ID
 * @param name       名称
 * @param maskedKey  打码后的密钥（仅用于辨认是哪一把）
 * @param status     状态
 * @param quota      额度上限（USD，0 = 不限）
 * @param quotaUsed  已用额度（USD）
 * @param expiresAt  到期时间（null = 永不过期）
 * @param lastUsedAt 最近使用时间
 * @param usage5h    近 5 小时用量
 * @param usage1d    近 1 天用量
 * @param usage7d    近 7 天用量
 */
public record Sub2AdminApiKey(
        Long id,
        String name,
        String maskedKey,
        String status,
        Double quota,
        Double quotaUsed,
        OffsetDateTime expiresAt,
        OffsetDateTime lastUsedAt,
        Double usage5h,
        Double usage1d,
        Double usage7d
) {
}