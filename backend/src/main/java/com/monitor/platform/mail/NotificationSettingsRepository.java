package com.monitor.platform.mail;

import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

/**
 * 邮件通知设置持久化仓储。
 */
@Repository
public class NotificationSettingsRepository {

    /** 默认余额提醒阈值。 */
    public static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("5");

    /** 默认重复提醒间隔（分钟），360 = 6 小时。 */
    public static final int DEFAULT_ALERT_INTERVAL_MINUTES = 360;

    private final NotificationSettingsMapper notificationSettingsMapper;

    public NotificationSettingsRepository(NotificationSettingsMapper notificationSettingsMapper) {
        this.notificationSettingsMapper = notificationSettingsMapper;
    }

    /** 读取通知设置；数据库中始终存在默认行，这里对空值做兜底。 */
    public NotificationSettingsEntity get() {
        NotificationSettingsEntity entity = notificationSettingsMapper.selectSettings();
        if (entity == null) {
            entity = new NotificationSettingsEntity();
            entity.setId(1);
            entity.setBalanceAlertEnabled(true);
        }
        if (entity.getBalanceThreshold() == null) {
            entity.setBalanceThreshold(DEFAULT_THRESHOLD);
        }
        if (entity.getAlertIntervalMinutes() == null || entity.getAlertIntervalMinutes() <= 0) {
            entity.setAlertIntervalMinutes(DEFAULT_ALERT_INTERVAL_MINUTES);
        }
        return entity;
    }

    /** 保存通知设置（单行 upsert）。 */
    public NotificationSettingsEntity save(NotificationSettingsEntity entity) {
        entity.setId(1);
        notificationSettingsMapper.upsertSettings(entity);
        return entity;
    }
}