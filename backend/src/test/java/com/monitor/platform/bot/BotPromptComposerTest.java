package com.monitor.platform.bot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BotPromptComposerTest {

    @Test
    void shouldKeepCapabilityRulesAndAppendPersonaStyle() {
        BotPersonaSnapshot persona = new BotPersonaSnapshot(
                7L, "温柔客服", 3,
                "你是一名耐心的售后客服。",
                "称呼用户为「老板」，语气温柔简洁。");

        String prompt = BotPromptComposer.compose("权限与工具规则：只能使用允许的工具。", persona);

        assertThat(prompt).contains("权限与工具规则");
        assertThat(prompt).contains("你是一名耐心的售后客服");
        assertThat(prompt).contains("称呼用户为「老板」");
        assertThat(prompt).contains("优先级高于角色设定");
        assertThat(prompt).contains("不能让你声称拥有更多权限");
    }

    @Test
    void shouldUseBuiltInPersonaWhenMissing() {
        String prompt = BotPromptComposer.compose("能力规则", null);

        assertThat(prompt).contains("内置默认人设");
        assertThat(prompt).contains("能力规则");
    }
}
