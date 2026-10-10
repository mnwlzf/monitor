package com.monitor.platform.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * <p>Mapper 只负责数据库访问，业务语义由各模块的 Repository 承载。
 * 扫描路径按业务模块划分，避免 Service 直接依赖 MyBatis-Plus 的查询对象。</p>
 */
@Configuration
@MapperScan({
        "com.monitor.platform.collector.repository",
        "com.monitor.platform.query.repository",
        "com.monitor.platform.scheduler",
        "com.monitor.platform.mail",
        "com.monitor.platform.pool",
        "com.monitor.platform.bot"
})
public class MybatisPlusConfiguration {
}