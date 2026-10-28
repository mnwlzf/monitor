package com.monitor.platform.bot;

import org.springframework.stereotype.Repository;

/** 抽奖配置仓储。 */
@Repository
public class BotLotterySettingsRepository {

    private final BotLotterySettingsMapper mapper;

    public BotLotterySettingsRepository(BotLotterySettingsMapper mapper) {
        this.mapper = mapper;
    }

    public BotLotterySettingsEntity find() {
        return mapper.selectSettings();
    }

    public void save(BotLotterySettingsEntity entity) {
        entity.setId(1);
        mapper.upsertSettings(entity);
    }
}
