package com.monitor.platform.mail;

import java.time.OffsetDateTime;

/**
 * 邮件收件人（按事件场景区分）。
 */
public class MailRecipientEntity {

    private Long id;
    private String scene;
    private String email;
    private String name;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}