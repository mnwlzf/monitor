package com.monitor.platform.mail;

import java.time.OffsetDateTime;

/**
 * 余额提醒邮件收件人。
 */
public class MailRecipientEntity {

    private Long id;
    private String email;
    private String name;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}