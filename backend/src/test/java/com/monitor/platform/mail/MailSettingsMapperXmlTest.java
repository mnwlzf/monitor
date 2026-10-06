package com.monitor.platform.mail;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮件设置 Mapper XML 加载契约测试。
 */
class MailSettingsMapperXmlTest {

    @Test
    void shouldLoadMailSettingsMapperXml() throws Exception {
        Configuration configuration = new Configuration();
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/mail/*.xml");

        assertEquals(3, resources.length);
        for (Resource resource : resources) {
            try (InputStream inputStream = resource.getInputStream()) {
                new XMLMapperBuilder(inputStream, configuration, resource.getFilename(),
                        configuration.getSqlFragments()).parse();
            }
        }

        assertTrue(configuration.hasStatement("com.monitor.platform.mail.MailSettingsMapper.selectSettings"));
        assertTrue(configuration.hasStatement("com.monitor.platform.mail.MailSettingsMapper.upsertSettings"));
        assertTrue(configuration.hasStatement("com.monitor.platform.mail.MailRecipientMapper.selectByScene"));
        assertTrue(configuration.hasStatement("com.monitor.platform.mail.MailRecipientMapper.insert"));
        assertTrue(configuration.hasStatement("com.monitor.platform.mail.NotificationSettingsMapper.selectSettings"));
        assertTrue(configuration.hasStatement("com.monitor.platform.mail.NotificationSettingsMapper.upsertSettings"));
    }
}