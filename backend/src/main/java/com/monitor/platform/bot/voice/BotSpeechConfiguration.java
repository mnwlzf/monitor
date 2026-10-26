package com.monitor.platform.bot.voice;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册语音能力配置属性；即使语音关闭，属性也必须始终可注入。 */
@Configuration
@EnableConfigurationProperties(BotSpeechProperties.class)
public class BotSpeechConfiguration {
}