package com.monitor.platform.bot.weather;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册天气查询配置属性。
 *
 * <p>单独放一个无条件生效的配置类：天气功能可能被关掉，但配置属性必须始终可用，
 * 否则依赖它的 Bean 会注入失败。</p>
 */
@Configuration
@EnableConfigurationProperties(WeatherProperties.class)
public class WeatherConfiguration {
}