package com.monitor.platform.bot.archive;

import java.time.OffsetDateTime;

/**
 * 一条机器人消息存档。
 *
 * <p>一问一答各存一行：{@code IN} 是用户发的，{@code OUT} 是机器人回的，
 * 用 {@code correlationId} 关联（应用生成，两条记录可独立异步写入）。</p>
 */
public class BotMessageArchiveEntity {

    private Long id;
    private OffsetDateTime createdAt;
    /** IN = 用户发给机器人，OUT = 机器人回复。 */
    private String direction;
    /** private / group。 */
    private String messageType;
    private Long groupId;
    private Long userId;
    /** 身份识别结果快照。 */
    private String senderEmail;
    /** ADMIN / USER / GUEST。 */
    private String senderRole;
    /** 机器人回复时用的人设（仅 OUT）。 */
    private String persona;
    private String content;
    /** TEXT / IMAGE / COMMAND。 */
    private String contentKind;
    /** 同一问一答共用的关联 ID（应用生成）。 */
    private String correlationId;
    private String messageId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getSenderEmail() { return senderEmail; }
    public void setSenderEmail(String senderEmail) { this.senderEmail = senderEmail; }
    public String getSenderRole() { return senderRole; }
    public void setSenderRole(String senderRole) { this.senderRole = senderRole; }
    public String getPersona() { return persona; }
    public void setPersona(String persona) { this.persona = persona; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentKind() { return contentKind; }
    public void setContentKind(String contentKind) { this.contentKind = contentKind; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
}