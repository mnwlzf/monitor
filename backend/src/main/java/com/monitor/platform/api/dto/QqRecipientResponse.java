package com.monitor.platform.api.dto;

import java.time.OffsetDateTime;

/**
 * QQ 消息接收人响应。
 *
 * @param id         接收人主键
 * @param scene      事件场景
 * @param targetType 接收对象类型：GROUP（群）/ PRIVATE（私聊）
 * @param targetId   群号或 QQ 号
 * @param name       备注名称
 * @param createdAt  创建时间
 */
public record QqRecipientResponse(
        Long id,
        String scene,
        String targetType,
        Long targetId,
        String name,
        OffsetDateTime createdAt
) {
}
