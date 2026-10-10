package com.monitor.platform.bot.user;

import java.time.OffsetDateTime;

/**
 * Sub2API 管理端 {@code GET /api/v1/admin/users} 返回的平台用户（只取用户端要展示的字段）。
 *
 * @param id             用户 ID
 * @param email          邮箱
 * @param username       用户名
 * @param role           角色
 * @param balance        余额
 * @param frozenBalance  冻结余额
 * @param totalRecharged 累计充值
 * @param status         账号状态
 * @param concurrency    并发上限
 * @param lastActiveAt   最近活跃时间
 */
public record Sub2AdminUser(
        Long id,
        String email,
        String username,
        String role,
        Double balance,
        Double frozenBalance,
        Double totalRecharged,
        String status,
        Integer concurrency,
        OffsetDateTime lastActiveAt
) {
}