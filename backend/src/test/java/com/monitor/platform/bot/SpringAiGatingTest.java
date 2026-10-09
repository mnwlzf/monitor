package com.monitor.platform.bot;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大模型默认关闭：{@code spring.ai.model.chat=none} 时不能创建 ChatModel，
 * 否则 Spring AI 会因为缺少 api-key 直接让应用起不来。
 */
class SpringAiGatingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RestClientAutoConfiguration.class,
                    SpringAiRetryAutoConfiguration.class,
                    ToolCallingAutoConfiguration.class,
                    OpenAiChatAutoConfiguration.class));

    @Test
    void shouldNotCreateChatModelWhenDisabled() {
        runner.withPropertyValues("spring.ai.model.chat=none")
                .run(context -> assertTrue(context.getBeansOfType(ChatModel.class).isEmpty(),
                        "关闭时不应创建 ChatModel"));
    }

    @Test
    void shouldCreateChatModelWhenEnabled() {
        runner.withPropertyValues(
                        "spring.ai.model.chat=openai",
                        "spring.ai.openai.api-key=test-key")
                .run(context -> assertFalse(context.getBeansOfType(ChatModel.class).isEmpty(),
                        "开启且配置了 key 时应创建 ChatModel"));
    }
}