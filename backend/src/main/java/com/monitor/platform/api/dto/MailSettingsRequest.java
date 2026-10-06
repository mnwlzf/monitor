package com.monitor.platform.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 保存 SMTP 邮件设置请求。
 *
 * <p>{@code password} 为空表示保留已保存的密码，避免每次保存都要重新输入。</p>
 *
 * @param enabled  是否启用邮件通知
 * @param host     SMTP 服务器地址
 * @param port     SMTP 服务器端口
 * @param username SMTP 登录用户名
 * @param password SMTP 登录密码，留空表示保留原值
 * @param from     发件人邮箱
 * @param fromName 发件人名称
 * @param useTls   是否使用隐式 TLS（465）
 */
public record MailSettingsRequest(
        Boolean enabled,

        @Size(max = 255, message = "SMTP 主机不能超过 255 个字符")
        String host,

        @Min(value = 1, message = "SMTP 端口范围 1-65535")
        @Max(value = 65535, message = "SMTP 端口范围 1-65535")
        Integer port,

        @Size(max = 255, message = "SMTP 用户名不能超过 255 个字符")
        String username,

        @Size(max = 255, message = "SMTP 密码不能超过 255 个字符")
        String password,

        @Size(max = 320, message = "发件人邮箱不能超过 320 个字符")
        String from,

        @Size(max = 120, message = "发件人名称不能超过 120 个字符")
        String fromName,

        Boolean useTls
) {
}