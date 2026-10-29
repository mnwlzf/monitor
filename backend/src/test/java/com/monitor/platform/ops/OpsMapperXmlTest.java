package com.monitor.platform.ops;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsMapperXmlTest {

    @Test
    void shouldLoadOpsMapperXml() throws Exception {
        Configuration configuration = new Configuration();
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/ops/*.xml");

        assertEquals(4, resources.length);
        for (Resource resource : resources) {
            try (InputStream inputStream = resource.getInputStream()) {
                new XMLMapperBuilder(inputStream, configuration, resource.getFilename(),
                        configuration.getSqlFragments()).parse();
            }
        }

        assertTrue(configuration.hasStatement("com.monitor.platform.ops.OpsIncidentMapper.selectById"));
        assertTrue(configuration.hasStatement("com.monitor.platform.ops.OpsIncidentMapper.insert"));
        assertTrue(configuration.hasStatement("com.monitor.platform.ops.OpsActionPlanMapper.claimForExecution"));
        assertTrue(configuration.hasStatement("com.monitor.platform.ops.OpsActionExecutionMapper.markFinished"));
    }
}