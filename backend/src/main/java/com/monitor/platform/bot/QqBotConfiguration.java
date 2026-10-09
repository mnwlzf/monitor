package com.monitor.platform.bot;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 {@link QqBotProperties}。
 *
 * <p>属性必须始终可用（未启用时相关 Bean 也要能注入），所以这里无条件生效。</p>
 */
@Configuration
@EnableConfigurationProperties(QqBotProperties.class)
public class QqBotConfiguration {
}