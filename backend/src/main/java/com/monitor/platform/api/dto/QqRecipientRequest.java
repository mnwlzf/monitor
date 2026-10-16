package com.monitor.platform.api.dto;

import com.monitor.platform.mail.MailScene;
import com.monitor.platform.notify.QqTargetType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 新增 QQ 消息接收人请求。
 *
 * @param scene      事件场景，与邮件收件人共用同一套场景
 * @param targetType 接收对象类型：群 / 私聊
 * @param targetId   群号或 QQ 号
 * @param name       备注名称，可选
 */
public record QqRecipientRequest(
        @NotNull(message = "事件场景不能为空")
        MailScene scene,

        @NotNull(message = "接收对象类型不能为空（GROUP / PRIVATE）")
        QqTargetType targetType,

        @NotNull(message = "群号 / QQ 号不能为空")
        @Positive(message = "群号 / QQ 号必须是正整数")
        Long targetId,

        @Size(max = 120, message = "接收人名称不能超过 120 个字符")
        String name
) {
}
