package com.monitor.platform.bot;

import org.springframework.stereotype.Repository;

/**
 * QQ 机器人设置持久化仓储。
 */
@Repository
public class BotSettingsRepository {

    private final BotSettingsMapper botSettingsMapper;

    public BotSettingsRepository(BotSettingsMapper botSettingsMapper) {
        this.botSettingsMapper = botSettingsMapper;
    }

    /** 读取设置；从未在页面保存过时返回 null（由上层用环境变量初始化）。 */
    public BotSettingsEntity find() {
        return botSettingsMapper.selectSettings();
    }

    /** 保存设置（单行 upsert）。 */
    public void save(BotSettingsEntity entity) {
        entity.setId(1);
        botSettingsMapper.upsertSettings(entity);
    }
}