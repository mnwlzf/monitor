package com.monitor.platform.bot.image;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册生图配置属性。
 *
 * <p>单独放一个无条件生效的配置类：生图功能可能被关掉，但配置属性必须始终可用，
 * 否则依赖它的 {@link ImageGenService} 会注入失败、应用直接起不来。</p>
 *
 * <p>踩过的坑：{@code @ConfigurationProperties} 本身<strong>只是声明</strong>，
 * 没有这个 {@code @EnableConfigurationProperties} 它就不会被注册成 Bean ——
 * 而单元测试大多是 mock 或 standalone 的，不会启动 Spring 上下文，于是本地全绿、
 * 线上容器却 crash-loop。{@code ConfigurationPropertiesRegistrationTest} 就是为此加的。</p>
 */
@Configuration
@EnableConfigurationProperties(ImageGenProperties.class)
public class ImageGenConfiguration {
}
