package com.monitor.platform.mail;

import org.springframework.stereotype.Repository;

/**
 * SMTP 邮件设置持久化仓储。
 */
@Repository
public class MailSettingsRepository {

    private final MailSettingsMapper mailSettingsMapper;

    public MailSettingsRepository(MailSettingsMapper mailSettingsMapper) {
        this.mailSettingsMapper = mailSettingsMapper;
    }

    /** 读取当前 SMTP 设置；数据库中始终存在默认行，这里对空值做兜底。 */
    public MailSettingsEntity get() {
        MailSettingsEntity entity = mailSettingsMapper.selectSettings();
        return entity != null ? entity : defaultEntity();
    }

    /** 保存 SMTP 设置（单行 upsert）。 */
    public MailSettingsEntity save(MailSettingsEntity entity) {
        entity.setId(1);
        mailSettingsMapper.upsertSettings(entity);
        return entity;
    }

    private MailSettingsEntity defaultEntity() {
        MailSettingsEntity entity = new MailSettingsEntity();
        entity.setId(1);
        entity.setEnabled(false);
        entity.setPort(587);
        entity.setUseTls(false);
        entity.setFromName("Monitor");
        return entity;
    }
}