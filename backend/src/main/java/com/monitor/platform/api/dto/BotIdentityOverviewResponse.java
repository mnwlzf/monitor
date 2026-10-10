package com.monitor.platform.api.dto;

import java.util.List;

/**
 * QQ 机器人身份识别总览（页面展示用）。
 *
 * @param enabled          身份识别总开关
 * @param qqLocalPartMatch 是否允许按 {@code <QQ号>@xx.com} 自动匹配
 * @param sub2Api          Sub2API 侧的用户缓存情况
 * @param customAdmins     监控项目侧登记的自定义管理员
 */
public record BotIdentityOverviewResponse(
        boolean enabled,
        boolean qqLocalPartMatch,
        Sub2ApiUsers sub2Api,
        List<BotAdminResponse> customAdmins
) {

    /**
     * Sub2API 侧用户缓存概况。
     *
     * @param available  是否配置了可用的只读库
     * @param userCount  缓存中的平台用户数
     * @param adminCount 缓存中 role=admin 的用户数
     * @param syncedAt   最近一次同步时间（ISO 字符串），从未同步为 null
     */
    public record Sub2ApiUsers(boolean available, long userCount, long adminCount, String syncedAt) {
    }
}