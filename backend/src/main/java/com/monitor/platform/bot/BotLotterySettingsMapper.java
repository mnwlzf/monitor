package com.monitor.platform.bot;

/** 抽奖配置 Mapper。 */
public interface BotLotterySettingsMapper {

    BotLotterySettingsEntity selectSettings();

    int upsertSettings(BotLotterySettingsEntity entity);
}
