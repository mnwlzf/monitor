package com.monitor.platform.mail;

/**
 * SMTP 邮件设置 Mapper。
 *
 * <p>全局单行，读取一行、upsert 一行即可。</p>
 */
public interface MailSettingsMapper {

    MailSettingsEntity selectSettings();

    int upsertSettings(MailSettingsEntity entity);
}