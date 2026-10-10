package com.monitor.platform.bot.identity;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册身份识别相关配置属性。
 *
 * <p>单独放一个无条件生效的配置类：识别功能本身在未配置平台时会自行跳过，
 * 但配置属性必须始终可用，否则依赖它的 Bean 会注入失败。</p>
 */
@Configuration
@EnableConfigurationProperties(BotIdentityProperties.class)
public class BotIdentityConfiguration {
}