package com.monitor.platform.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 基础配置。
 *
 * <p>Mapper 只负责数据库访问，业务语义仍由各模块的 Repository 适配器承载，
 * 避免 Service 直接依赖 MyBatis-Plus 的查询对象。</p>
 */
@Configuration
@MapperScan({
        "com.monitor.platform.upstream.infrastructure.mybatis.mapper",
        "com.monitor.platform.collection.infrastructure.mybatis.mapper",
        "com.monitor.platform.security.admin.infrastructure.mybatis.mapper"
})
public class MybatisPlusConfiguration {
}
