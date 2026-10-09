package com.monitor.platform.pool.ingest;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 {@link PoolIngestProperties}。
 *
 * <p>单独放一个「无条件生效」的配置类：直连数据源本身是有条件的（未配置就不创建），
 * 但配置属性必须始终可用，否则未配置时依赖它的 Bean 会注入失败。</p>
 */
@Configuration
@EnableConfigurationProperties(PoolIngestProperties.class)
public class PoolIngestPropertiesConfiguration {
}