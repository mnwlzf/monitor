package com.monitor.platform.api.dto;

import java.time.OffsetDateTime;

/**
 * 机器人消息存档（页面展示用）。
 *
 * @param id          主键
 * @param createdAt   发生时间
 * @param direction   IN = 用户发给机器人，OUT = 机器人回复
 * @param messageType private / group
 * @param groupId     群号（私聊为空）
 * @param userId      发送者 QQ
 * @param senderEmail 存档时的身份识别结果
 * @param senderRole  存档时的身份：ADMIN / USER / GUEST
 * @param persona     机器人回复用的人设（仅 OUT）
 * @param content     消息内容；图片消息是摘要
 * @param contentKind TEXT / IMAGE / COMMAND
 * @param correlationId 同一问一答共用的关联 ID
 * @param messageId   OneBot 侧的消息 ID
 */
public record BotMessageResponse(
        Long id,
        OffsetDateTime createdAt,
        String direction,
        String messageType,
        Long groupId,
        Long userId,
        String senderEmail,
        String senderRole,
        String persona,
        String content,
        String contentKind,
        String correlationId,
        String messageId
) {
}