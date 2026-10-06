package com.monitor.platform.api.dto;

import java.time.OffsetDateTime;

/**
 * SMTP 邮件设置响应。
 *
 * <p>出于安全考虑不返回密码明文，仅通过 {@code passwordConfigured} 告知前端
 * 是否已配置密码，前端据此提示「留空以保留当前值」。</p>
 *
 * @param enabled            是否启用邮件通知
 * @param host               SMTP 服务器地址
 * @param port               SMTP 服务器端口
 * @param username           SMTP 登录用户名
 * @param passwordConfigured 是否已配置密码
 * @param from               发件人邮箱
 * @param fromName           发件人名称
 * @param useTls             是否使用隐式 TLS（465）
 * @param updatedAt          最近更新时间
 */
public record MailSettingsResponse(
        boolean enabled,
        String host,
        int port,
        String username,
        boolean passwordConfigured,
        String from,
        String fromName,
        boolean useTls,
        OffsetDateTime updatedAt
) {
}