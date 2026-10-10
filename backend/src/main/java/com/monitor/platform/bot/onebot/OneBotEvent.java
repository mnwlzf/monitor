package com.monitor.platform.bot.onebot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * OneBot v11 事件（只取用得到的字段，其余忽略）。
 *
 * <p>{@code message} 可能是消息段数组，也可能因为配置了 {@code post_format=string} 而是字符串，
 * 所以用 {@link JsonNode} 承载，两种都能解析。</p>
 *
 * @param postType   事件类型：message / notice / request / meta_event
 * @param messageType 消息类型：group / private
 * @param subType    私聊子类型：friend（好友私聊）/ group（群临时会话）/ other。
 *                   群消息为空；群临时会话必须靠它才能和好友私聊区分开
 * @param messageId  该条消息在 OneBot 侧的 ID，用于和 QQ 客户端对账
 * @param groupId    群号；群消息是本群，群临时会话是发起该会话的群，好友私聊为空
 * @param userId     发送者 QQ
 * @param selfId     机器人自己的 QQ（用于判断是否被 @）
 * @param rawMessage 原始文本（含 CQ 码）
 * @param message    消息段
 * @param sender     发送者信息
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OneBotEvent(
        @JsonProperty("post_type") String postType,
        @JsonProperty("message_type") String messageType,
        @JsonProperty("sub_type") String subType,
        @JsonProperty("message_id") String messageId,
        @JsonProperty("group_id") Long groupId,
        @JsonProperty("user_id") Long userId,
        @JsonProperty("self_id") Long selfId,
        @JsonProperty("raw_message") String rawMessage,
        JsonNode message,
        JsonNode sender
) {
}