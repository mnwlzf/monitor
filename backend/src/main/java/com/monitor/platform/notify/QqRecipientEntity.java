package com.monitor.platform.notify;

import java.time.OffsetDateTime;

/**
 * QQ 消息接收人（按事件场景区分）。
 *
 * <p>与 {@code MailRecipientEntity} 平行：邮件收件人是邮箱，这里是群号 / QQ 号，
 * 两者互不依赖 —— 只配了 QQ 也能收到提醒。</p>
 */
public class QqRecipientEntity {

    private Long id;
    /** 事件场景，取值同 {@code MailScene}。 */
    private String scene;
    /** {@link QqTargetType} 的名字：GROUP / PRIVATE。 */
    private String targetType;
    /** 群号或 QQ 号。 */
    private Long targetId;
    /** 备注名称，可选。 */
    private String name;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
