package com.monitor.platform.bot.archive;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册消息存档配置属性。
 */
@Configuration
@EnableConfigurationProperties(BotMessageArchiveProperties.class)
public class BotMessageArchiveConfiguration {
}