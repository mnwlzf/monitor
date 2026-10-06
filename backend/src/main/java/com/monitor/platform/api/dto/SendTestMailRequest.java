package com.monitor.platform.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 发送测试邮件请求。
 *
 * @param to       收件人邮箱
 * @param settings 可选的临时 SMTP 配置；为空时使用已保存的配置
 */
public record SendTestMailRequest(
        @NotBlank(message = "收件人邮箱不能为空")
        @Email(message = "收件人邮箱格式不正确")
        String to,

        MailSettingsRequest settings
) {
}