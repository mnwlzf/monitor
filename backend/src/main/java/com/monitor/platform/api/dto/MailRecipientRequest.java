package com.monitor.platform.api.dto;

import com.monitor.platform.mail.MailScene;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 新增邮件收件人请求。
 *
 * @param scene 事件场景，不同事件使用各自的收件人
 * @param email 收件人邮箱
 * @param name  收件人名称，可选
 */
public record MailRecipientRequest(
        @NotNull(message = "事件场景不能为空")
        MailScene scene,

        @NotBlank(message = "收件人邮箱不能为空")
        @Email(message = "收件人邮箱格式不正确")
        @Size(max = 320, message = "收件人邮箱不能超过 320 个字符")
        String email,

        @Size(max = 120, message = "收件人名称不能超过 120 个字符")
        String name
) {
}