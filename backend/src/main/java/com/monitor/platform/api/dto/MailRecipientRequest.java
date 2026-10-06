package com.monitor.platform.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增余额提醒收件人请求。
 *
 * @param email 收件人邮箱
 * @param name  收件人名称，可选
 */
public record MailRecipientRequest(
        @NotBlank(message = "收件人邮箱不能为空")
        @Email(message = "收件人邮箱格式不正确")
        @Size(max = 320, message = "收件人邮箱不能超过 320 个字符")
        String email,

        @Size(max = 120, message = "收件人名称不能超过 120 个字符")
        String name
) {
}