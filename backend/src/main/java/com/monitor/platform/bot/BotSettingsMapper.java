package com.monitor.platform.bot;

/**
 * QQ 机器人设置 Mapper。
 *
 * <p>全局单行，读取一行、upsert 一行即可。</p>
 */
public interface BotSettingsMapper {

    BotSettingsEntity selectSettings();

    int upsertSettings(BotSettingsEntity entity);
}