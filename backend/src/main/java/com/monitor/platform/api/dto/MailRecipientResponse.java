package com.monitor.platform.api.dto;

import java.time.OffsetDateTime;

/**
 * 邮件收件人响应。
 *
 * @param id        收件人主键
 * @param scene     事件场景
 * @param email     收件人邮箱
 * @param name      收件人名称
 * @param createdAt 创建时间
 */
public record MailRecipientResponse(
        Long id,
        String scene,
        String email,
        String name,
        OffsetDateTime createdAt
) {
}