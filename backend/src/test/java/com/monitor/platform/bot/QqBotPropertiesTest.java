package com.monitor.platform.bot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机器人配置绑定：空环境变量不能把应用带崩，白名单为空时语义是「不限制」。
 */
class QqBotPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void shouldTreatBlankWhitelistAsUnrestricted() {
        runner.withPropertyValues(
                        "monitor.bot.enabled=true",
                        "monitor.bot.webhook-token=secret",
                        "monitor.bot.api-base-url=http://127.0.0.1:3000",
                        "monitor.bot.allowed-groups=",
                        "monitor.bot.allowed-users=")
                .run(context -> {
                    QqBotProperties properties = context.getBean(QqBotProperties.class);
                    assertTrue(properties.isConfigured());
                    assertTrue(properties.isGroupAllowed(123456L));
                    assertTrue(properties.isUserAllowed(123456L));
                });
    }

    @Test
    void shouldRestrictToConfiguredWhitelist() {
        runner.withPropertyValues(
                        "monitor.bot.enabled=true",
                        "monitor.bot.webhook-token=secret",
                        "monitor.bot.api-base-url=http://127.0.0.1:3000",
                        "monitor.bot.allowed-groups=111,222")
                .run(context -> {
                    QqBotProperties properties = context.getBean(QqBotProperties.class);
                    assertTrue(properties.isGroupAllowed(111L));
                    assertTrue(properties.isGroupAllowed(222L));
                    assertFalse(properties.isGroupAllowed(333L));
                });
    }

    @Test
    void shouldRequireTokenAndApiUrlToBeConfigured() {
        runner.withPropertyValues("monitor.bot.enabled=true")
                .run(context -> assertFalse(context.getBean(QqBotProperties.class).isConfigured()));
    }

    @Configuration
    @EnableConfigurationProperties(QqBotProperties.class)
    static class TestConfiguration {
    }
}