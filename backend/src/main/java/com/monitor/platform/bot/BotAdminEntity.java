package com.monitor.platform.bot;

import java.time.OffsetDateTime;

/**
 * 监控项目侧的自定义管理员。
 *
 * <p>与 Sub2API 自带的管理员角色取并集：Sub2API 只允许一个管理员，
 * 实际运维需要多人时在这里追加。</p>
 */
public class BotAdminEntity {

    private Long id;
    private String email;
    private String remark;
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}