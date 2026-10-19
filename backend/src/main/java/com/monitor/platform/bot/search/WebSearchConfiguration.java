package com.monitor.platform.bot.search;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册联网搜索配置属性。
 *
 * <p>与天气一样单独放一个无条件生效的配置类：功能可能被关掉，但属性必须始终可绑定，
 * 否则依赖它的 Bean 会注入失败。</p>
 */
@Configuration
@EnableConfigurationProperties(WebSearchProperties.class)
public class WebSearchConfiguration {
}
