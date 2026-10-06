package com.monitor.platform.api.dto;

import java.time.OffsetDateTime;

/**
 * 余额提醒收件人响应。
 *
 * @param id        收件人主键
 * @param email     收件人邮箱
 * @param name      收件人名称
 * @param createdAt 创建时间
 */
public record MailRecipientResponse(
        Long id,
        String email,
        String name,
        OffsetDateTime createdAt
) {
}