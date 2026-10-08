package com.monitor.platform.pool.repository.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 号池 Mapper XML 加载契约测试。
 */
class PoolMapperXmlContractTest {

    @Test
    void shouldLoadAllPoolMapperXmlFiles() throws Exception {
        Configuration configuration = new Configuration();
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/pool/*.xml");

        assertEquals(2, resources.length);
        for (Resource resource : resources) {
            try (InputStream inputStream = resource.getInputStream()) {
                XMLMapperBuilder builder = new XMLMapperBuilder(
                        inputStream,
                        configuration,
                        resource.getFilename(),
                        configuration.getSqlFragments()
                );
                builder.parse();
            }
        }

        assertTrue(configuration.hasStatement(
                "com.monitor.platform.pool.repository.mapper.PoolAccountMapper.upsertAccount"));
        assertTrue(configuration.hasStatement(
                "com.monitor.platform.pool.repository.mapper.PoolAccountMapper.selectByPlatform"));
        assertTrue(configuration.hasStatement(
                "com.monitor.platform.pool.repository.mapper.PoolSampleMapper.insertSamples"));
        assertTrue(configuration.hasStatement(
                "com.monitor.platform.pool.repository.mapper.PoolSampleMapper.selectSeries"));
        assertTrue(configuration.hasStatement(
                "com.monitor.platform.pool.repository.mapper.PoolSampleMapper.selectByModel"));
    }
}