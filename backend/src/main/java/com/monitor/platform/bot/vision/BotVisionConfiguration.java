package com.monitor.platform.bot.vision;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册图片理解配置属性。
 */
@Configuration
@EnableConfigurationProperties(BotVisionProperties.class)
public class BotVisionConfiguration {
}