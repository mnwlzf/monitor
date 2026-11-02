package com.monitor.platform.bot;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BotPersonaMapperXmlTest {

    @Test
    void shouldLoadPersonaMapperStatements() throws Exception {
        Configuration configuration = new Configuration();
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/bot/BotPersonaMapper.xml");
        for (Resource resource : resources) {
            try (InputStream inputStream = resource.getInputStream()) {
                new XMLMapperBuilder(inputStream, configuration, resource.getFilename(),
                        configuration.getSqlFragments()).parse();
            }
        }

        assertTrue(configuration.hasStatement("com.monitor.platform.bot.BotPersonaMapper.findAll"));
        assertTrue(configuration.hasStatement("com.monitor.platform.bot.BotPersonaMapper.findById"));
        assertTrue(configuration.hasStatement("com.monitor.platform.bot.BotPersonaMapper.findDefault"));
        assertTrue(configuration.hasStatement("com.monitor.platform.bot.BotPersonaMapper.countGroupBindings"));
    }
}
