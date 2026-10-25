package com.monitor.platform.status;

import java.time.OffsetDateTime;

/**
 * OpenAI 服务状态告警状态（数据库单行）。
 */
public class OpenAiStatusStateEntity {

    private Integer id;
    private Boolean alertActive;
    private Boolean alertSent;
    private String lastIndicator;
    private String lastDescription;
    private OffsetDateTime lastCheckedAt;
    private OffsetDateTime lastChangedAt;
    private OffsetDateTime lastAlertedAt;
    private OffsetDateTime lastRecoveredAt;
    private OffsetDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Boolean getAlertActive() { return alertActive; }
    public void setAlertActive(Boolean alertActive) { this.alertActive = alertActive; }
    public Boolean getAlertSent() { return alertSent; }
    public void setAlertSent(Boolean alertSent) { this.alertSent = alertSent; }
    public String getLastIndicator() { return lastIndicator; }
    public void setLastIndicator(String lastIndicator) { this.lastIndicator = lastIndicator; }
    public String getLastDescription() { return lastDescription; }
    public void setLastDescription(String lastDescription) { this.lastDescription = lastDescription; }
    public OffsetDateTime getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(OffsetDateTime lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
    public OffsetDateTime getLastChangedAt() { return lastChangedAt; }
    public void setLastChangedAt(OffsetDateTime lastChangedAt) { this.lastChangedAt = lastChangedAt; }
    public OffsetDateTime getLastAlertedAt() { return lastAlertedAt; }
    public void setLastAlertedAt(OffsetDateTime lastAlertedAt) { this.lastAlertedAt = lastAlertedAt; }
    public OffsetDateTime getLastRecoveredAt() { return lastRecoveredAt; }
    public void setLastRecoveredAt(OffsetDateTime lastRecoveredAt) { this.lastRecoveredAt = lastRecoveredAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}