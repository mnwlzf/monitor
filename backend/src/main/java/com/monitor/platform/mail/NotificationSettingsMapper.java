package com.monitor.platform.mail;

/**
 * 邮件通知设置 Mapper（全局单行）。
 */
public interface NotificationSettingsMapper {

    NotificationSettingsEntity selectSettings();

    int upsertSettings(NotificationSettingsEntity entity);
}