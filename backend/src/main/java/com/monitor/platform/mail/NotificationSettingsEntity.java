package com.monitor.platform.mail;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 邮件通知设置（全局单行）。
 */
public class NotificationSettingsEntity {

    private Integer id;
    private Boolean balanceAlertEnabled;
    private BigDecimal balanceThreshold;
    private Integer alertIntervalMinutes;
    private OffsetDateTime lastAlertAt;
    private OffsetDateTime updatedAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Boolean getBalanceAlertEnabled() { return balanceAlertEnabled; }
    public void setBalanceAlertEnabled(Boolean balanceAlertEnabled) { this.balanceAlertEnabled = balanceAlertEnabled; }
    public BigDecimal getBalanceThreshold() { return balanceThreshold; }
    public void setBalanceThreshold(BigDecimal balanceThreshold) { this.balanceThreshold = balanceThreshold; }
    public Integer getAlertIntervalMinutes() { return alertIntervalMinutes; }
    public void setAlertIntervalMinutes(Integer alertIntervalMinutes) { this.alertIntervalMinutes = alertIntervalMinutes; }
    public OffsetDateTime getLastAlertAt() { return lastAlertAt; }
    public void setLastAlertAt(OffsetDateTime lastAlertAt) { this.lastAlertAt = lastAlertAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}