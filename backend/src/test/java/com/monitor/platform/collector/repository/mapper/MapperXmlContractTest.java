package com.monitor.platform.collector.repository.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapper XML 加载契约测试。
 */
class MapperXmlContractTest {

    @Test
    void shouldLoadAllCollectorMapperXmlFiles() throws Exception {
        Configuration configuration = new Configuration();
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/collector/*.xml");

        assertEquals(8, resources.length);
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
                "com.monitor.platform.collector.repository.mapper.PlatformMapper.selectEnabledPlatforms"));
        assertTrue(configuration.hasStatement(
                "com.monitor.platform.collector.repository.mapper.AccountMapper.selectDueAccounts"));
        assertTrue(configuration.hasStatement(
                "com.monitor.platform.collector.repository.mapper.AccountMetricSnapshotMapper.selectLatestSnapshot"));
        assertTrue(configuration.hasStatement(
                "com.monitor.platform.collector.repository.mapper.UpstreamGroupMapper.deactivateMissingGroups"));
        assertTrue(configuration.hasStatement(
                "com.monitor.platform.collector.repository.mapper.UpstreamChangeEventMapper.selectRecentEvents"));
    }
}